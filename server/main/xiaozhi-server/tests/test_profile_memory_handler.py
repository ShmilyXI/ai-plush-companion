from unittest.mock import AsyncMock

import pytest
from aiohttp import web

from core.api.companion_memory_handler import CompanionMemoryHandler


class FakeProvider:
    def __init__(self):
        self.items = [{"id": "m1", "content": "喜欢散步"}]
        self.calls = []

    async def list_memory_items(self):
        self.calls.append("list")
        return self.items

    async def update_memory_item(self, memory_id, content):
        self.calls.append(("update", memory_id, content))
        return True

    async def delete_memory_item(self, memory_id):
        self.calls.append(("delete", memory_id))
        return True

    async def clear_memory(self):
        self.calls.append("clear")
        return True

    def get_management_summary(self):
        return "ok"


@pytest.mark.asyncio
async def test_profile_memory_uses_explicit_canonical_namespace():
    provider = FakeProvider()
    handler = CompanionMemoryHandler(
        {"server": {"auth_key": "secret"}},
        memory_factory=lambda config, namespace, save, source_metadata=None: (
            provider if namespace == "companion:7:profile-a" else None
        ),
    )
    request = type("Request", (), {})()
    request.headers = {"Authorization": "Bearer secret"}
    request.json = AsyncMock(return_value={"user_id": 7, "profile_id": "profile-a", "memory_namespace": "companion:7:profile-a"})
    response = await handler.handle_profile(request)
    assert response.status == 200
    assert provider.calls == ["list"]


@pytest.mark.asyncio
async def test_profile_memory_rejects_namespace_mismatch():
    handler = CompanionMemoryHandler({"server": {"auth_key": "secret"}})
    request = type("Request", (), {})()
    request.headers = {"Authorization": "Bearer secret"}
    request.json = AsyncMock(return_value={"user_id": 7, "profile_id": "profile-a", "memory_namespace": "companion:8:profile-a"})
    with pytest.raises(web.HTTPBadRequest):
        await handler.handle_profile(request)


@pytest.mark.asyncio
async def test_profile_memory_rejects_boolean_user_id():
    handler = CompanionMemoryHandler({"server": {"auth_key": "secret"}})
    request = type("Request", (), {})()
    request.headers = {"Authorization": "Bearer secret"}
    request.json = AsyncMock(return_value={
        "user_id": True,
        "profile_id": "profile-a",
        "memory_namespace": "companion:1:profile-a",
    })

    with pytest.raises(web.HTTPBadRequest):
        await handler.handle_profile(request)
