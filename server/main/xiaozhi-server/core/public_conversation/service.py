from __future__ import annotations

from typing import Any
import httpx

from .protocol import RuntimeTokenClaims
from .session import PublicConversationSession
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
            return session
        bundle = await self.runtime_client.bundle(conversation_id)
        if bundle.get("conversation_id") != conversation_id:
            raise ValueError("runtime bundle conversation mismatch")
        if bundle.get("agent_id") != claims.agent_id or int(bundle.get("agent_version", 0)) != claims.agent_version:
            raise ValueError("runtime bundle agent mismatch")
        session = PublicConversationSession(claims, bundle)
        self.sessions[conversation_id] = session
        return session

    def close(self, conversation_id: str) -> None:
        self.sessions.pop(conversation_id, None)
