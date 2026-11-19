from __future__ import annotations

from typing import Any, Awaitable, Callable
import httpx

from .protocol import RuntimeTokenClaims
from .session import MAX_CONTEXT_TURNS, PublicConversationSession
from .token import verify_runtime_token


class PublicConversationRuntimeClient:
    def __init__(self, config: dict[str, Any]):
        manager = config.get("manager-api") or {}
        self.base_url = str(manager.get("url") or "").rstrip("/")
        self.secret = str(manager.get("secret") or "")

    async def bundle(self, conversation_id: str) -> dict[str, Any]:
        if not self.base_url or not self.secret:
            raise RuntimeError("manager-api runtime configuration is missing")
        async with httpx.AsyncClient(
            base_url=self.base_url,
            headers={"Authorization": f"Bearer {self.secret}"},
            timeout=10,
            trust_env=False,
        ) as client:
            response = await client.get(f"/internal/public-conversations/{conversation_id}/bundle")
            response.raise_for_status()
            data = response.json().get("data")
            if not isinstance(data, dict):
                raise RuntimeError("runtime bundle is invalid")
            return data

    async def append_history(self, conversation_id: str, item: dict[str, Any]) -> dict[str, Any] | None:
        """上报完成的一轮。返回 manager-api 给出的额度信息，例如今天还剩多少次。"""
        if not self.base_url or not self.secret:
            raise RuntimeError("manager-api runtime configuration is missing")
        async with httpx.AsyncClient(base_url=self.base_url,
                                     headers={"Authorization": f"Bearer {self.secret}"},
                                     timeout=10, trust_env=False) as client:
            response = await client.post(f"/internal/public-conversations/{conversation_id}/history", json=item)
            response.raise_for_status()
            data = response.json().get("data")
            return data if isinstance(data, dict) else None

    async def history(self, conversation_id: str, limit: int) -> list[dict[str, Any]]:
        if not self.base_url or not self.secret:
            raise RuntimeError("manager-api runtime configuration is missing")
        async with httpx.AsyncClient(base_url=self.base_url,
                                     headers={"Authorization": f"Bearer {self.secret}"},
                                     timeout=10, trust_env=False) as client:
            response = await client.get(f"/internal/public-conversations/{conversation_id}/history",
                                        params={"limit": limit})
            response.raise_for_status()
            data = response.json().get("data")
            if not isinstance(data, list):
                raise RuntimeError("runtime history is invalid")
            return [item for item in data if isinstance(item, dict)]


    async def thread(self, conversation_id: str, limit: int) -> list[dict[str, Any]]:
        """这个会话的用户和角色之前聊过的最近若干轮，按时间从早到晚。"""
        if not self.base_url or not self.secret:
            raise RuntimeError("manager-api runtime configuration is missing")
        async with httpx.AsyncClient(base_url=self.base_url,
                                     headers={"Authorization": f"Bearer {self.secret}"},
                                     timeout=5, trust_env=False) as client:
            response = await client.get(f"/internal/public-conversations/{conversation_id}/thread",
                                        params={"limit": limit})
            response.raise_for_status()
            data = response.json().get("data")
            if not isinstance(data, list):
                raise RuntimeError("runtime thread is invalid")
            return [item for item in data if isinstance(item, dict)]


class PublicConversationService:
    def __init__(self, config: dict[str, Any], runtime_client: PublicConversationRuntimeClient | None = None):
        manager = config.get("manager-api") or {}
        self.secret = str(manager.get("secret") or "")
        self.runtime_client = runtime_client or PublicConversationRuntimeClient(config)
        self.sessions: dict[str, PublicConversationSession] = {}

    async def open(self, conversation_id: str, token: str) -> PublicConversationSession:
        claims = verify_runtime_token(token, self.secret)
        if claims.conversation_id != conversation_id:
            raise ValueError("conversation does not match token")
        session = self.sessions.get(conversation_id)
        if session is not None:
            if (
                session.claims.subject != claims.subject
                or session.claims.agent_id != claims.agent_id
                or session.claims.agent_version != claims.agent_version
                or frozenset(session.claims.scopes) != frozenset(claims.scopes)
                or frozenset(session.claims.input_modes) != frozenset(claims.input_modes)
                or frozenset(session.claims.output_modes) != frozenset(claims.output_modes)
            ):
                raise ValueError("runtime token does not match session")
            return session
        bundle = await self.runtime_client.bundle(conversation_id)
        normalized_bundle = dict(bundle)
        normalized_bundle.setdefault("conversation_id", bundle.get("conversationId"))
        normalized_bundle.setdefault("agent_id", bundle.get("agentId"))
        normalized_bundle.setdefault("agent_version", bundle.get("agentVersion"))
        normalized_bundle.setdefault("runtime_models", bundle.get("runtimeModels"))
        if normalized_bundle.get("conversation_id") != conversation_id:
            raise ValueError("runtime bundle conversation mismatch")
        if normalized_bundle.get("agent_id") != claims.agent_id or int(normalized_bundle.get("agent_version", 0)) != claims.agent_version:
            raise ValueError("runtime bundle agent mismatch")
        loader = getattr(self.runtime_client, "history", None)
        writer = getattr(self.runtime_client, "append_history", None)
        session = PublicConversationSession(
            claims,
            normalized_bundle,
            history_loader=(lambda limit: loader(conversation_id, limit)) if callable(loader) else None,
            history_writer=(lambda item: writer(conversation_id, item)) if callable(writer) else None,
        )
        await self._restore_thread(conversation_id, normalized_bundle, session)
        self.sessions[conversation_id] = session
        return session

    async def _restore_thread(self, conversation_id: str, bundle: dict[str, Any],
                              session: PublicConversationSession) -> None:
        """让新会话接上这个用户和角色之前的对话。

        会话只是一次连接：令牌过期、断线、刷新页面都会换一个新会话。
        取不到历史时照常开始对话，不能因为这一步失败就连不上。
        """
        config = bundle.get("config") or {}
        loader = getattr(self.runtime_client, "thread", None)
        if not config.get("historyThread") or not callable(loader):
            return
        try:
            session.seed_history(await loader(conversation_id, MAX_CONTEXT_TURNS))
        except Exception:
            return

    def close(self, conversation_id: str) -> None:
        self.sessions.pop(conversation_id, None)
