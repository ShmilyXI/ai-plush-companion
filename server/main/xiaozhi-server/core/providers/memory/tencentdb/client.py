from __future__ import annotations

from typing import Any

import httpx


SERVICE_ID = "ai-plush-companion"
ROUTES = {
    "conversation_add": "/v3/conversation/add",
    "conversation_query": "/v3/conversation/query",
    "conversation_delete": "/v3/conversation/delete",
    "atomic_query": "/v3/atomic/query",
    "atomic_search": "/v3/atomic/search",
    "atomic_update": "/v3/atomic/update",
    "atomic_delete": "/v3/atomic/delete",
    "scenario_list": "/v3/scenario/ls",
    "scenario_remove": "/v3/scenario/rm",
    "core_read": "/v3/core/read",
    "core_write": "/v3/core/write",
}


class TencentDbMemoryError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        status: int | None = None,
        code: int | None = None,
        request_id: str | None = None,
        retryable: bool = False,
    ):
        super().__init__(message)
        self.status = status
        self.code = code
        self.request_id = request_id
        self.retryable = retryable


class TencentDbMemoryClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        timeout: float = 4.0,
        service_id: str = SERVICE_ID,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=timeout,
            transport=transport,
            headers={
                "Authorization": f"Bearer {api_key}",
                "x-tdai-service-id": service_id,
                "Content-Type": "application/json",
            },
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def health(self) -> dict[str, Any]:
        response = await self._request("GET", "/health")
        try:
            payload = response.json()
        except ValueError as exception:
            raise TencentDbMemoryError("MemoryCore 返回了无效 JSON") from exception
        return payload if isinstance(payload, dict) else {"value": payload}

    async def conversation_add(self, isolation: dict, session_id: str, messages: list[dict]) -> dict:
        return await self._post("conversation_add", isolation, session_id=session_id, messages=messages)

    async def conversation_query(
        self, isolation: dict, *, session_id: str | None = None, limit: int = 100, offset: int = 0
    ) -> dict:
        payload = {"limit": limit, "offset": offset}
        if session_id is not None:
            payload["session_id"] = session_id
        return await self._post("conversation_query", isolation, **payload)

    async def conversation_delete(
        self, isolation: dict, *, message_ids: list[str] | None = None, session_id: str | None = None
    ) -> dict:
        payload: dict[str, Any] = {}
        if message_ids is not None:
            payload["message_ids"] = message_ids
        if session_id is not None:
            payload["session_id"] = session_id
        return await self._post("conversation_delete", isolation, **payload)

    async def atomic_query(self, isolation: dict, *, limit: int = 100, offset: int = 0) -> dict:
        return await self._post("atomic_query", isolation, limit=limit, offset=offset)

    async def atomic_search(self, isolation: dict, query: str, *, limit: int = 8) -> dict:
        return await self._post("atomic_search", isolation, query=query, limit=limit)

    async def atomic_update(self, isolation: dict, memory_id: str, content: str) -> dict:
        return await self._post("atomic_update", isolation, id=memory_id, content=content)

    async def atomic_delete(self, isolation: dict, ids: list[str]) -> dict:
        return await self._post("atomic_delete", isolation, ids=ids)

    async def scenario_list(self, isolation: dict) -> dict:
        return await self._post("scenario_list", isolation)

    async def scenario_remove(self, isolation: dict, path: str) -> dict:
        return await self._post("scenario_remove", isolation, path=path)

    async def core_read(self, isolation: dict) -> dict:
        return await self._post("core_read", isolation)

    async def core_write(self, isolation: dict, content: str) -> dict:
        return await self._post("core_write", isolation, content=content)

    async def _post(self, route: str, isolation: dict, **values: Any) -> dict:
        self._validate_isolation(isolation)
        response = await self._request("POST", ROUTES[route], json={**isolation, **values})
        try:
            envelope = response.json()
        except ValueError as exception:
            raise TencentDbMemoryError(
                "MemoryCore 返回了无效 JSON", status=response.status_code,
                retryable=response.status_code >= 500,
            ) from exception
        if not isinstance(envelope, dict):
            raise TencentDbMemoryError("MemoryCore 返回了无效响应")
        code = envelope.get("code")
        request_id = envelope.get("request_id")
        if code != 0:
            raise TencentDbMemoryError(
                "MemoryCore 请求失败",
                status=response.status_code,
                code=code if isinstance(code, int) else None,
                request_id=request_id if isinstance(request_id, str) else None,
                retryable=response.status_code >= 500,
            )
        data = envelope.get("data")
        result = dict(data) if isinstance(data, dict) else {}
        if isinstance(request_id, str) and request_id:
            result["_request_id"] = request_id
        return result

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        try:
            response = await self._client.request(method, path, **kwargs)
        except (httpx.TimeoutException, httpx.NetworkError) as exception:
            raise TencentDbMemoryError(
                "MemoryCore 网络请求失败", retryable=True
            ) from exception
        if response.status_code < 200 or response.status_code >= 300:
            raise TencentDbMemoryError(
                f"MemoryCore 返回 HTTP {response.status_code}",
                status=response.status_code,
                retryable=response.status_code == 429 or response.status_code >= 500,
            )
        return response

    def _validate_isolation(self, isolation: dict) -> None:
        for key in ("team_id", "user_id", "agent_id"):
            if not isolation.get(key):
                raise ValueError(f"{key} 不能为空")
