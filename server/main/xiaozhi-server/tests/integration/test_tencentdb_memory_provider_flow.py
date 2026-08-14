import sys
import types

import pytest
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
sys.modules["config.logger"] = logger_module

from core.providers.memory.tencentdb.tencentdb import MemoryProvider
from tests.integration.fake_tencentdb_memory_core import FakeTencentDbMemoryCore


def provider(base_url, *, user_id, profile_id, device_id):
    instance = MemoryProvider({
        "memory_core_url": base_url,
        "memory_core_api_key": "test-core",
    })
    instance.init_memory(
        "companion:" + "a" * 64,
        llm=None,
        source_metadata={
            "source_user_id": user_id,
            "source_profile_id": profile_id,
            "source_device_id": device_id,
        },
    )
    return instance


@pytest.mark.asyncio
async def test_full_flow_isolates_user_profile_and_shares_across_devices():
    async with FakeTencentDbMemoryCore("test-core") as core:
        device_a = provider(core.base_url, user_id=7, profile_id="profile-a", device_id="device-a")
        device_b = provider(core.base_url, user_id=7, profile_id="profile-a", device_id="device-b")
        profile_b = provider(core.base_url, user_id=7, profile_id="profile-b", device_id="device-a")
        other_user = provider(core.base_url, user_id=8, profile_id="profile-a", device_id="device-c")
        providers = [device_a, device_b, profile_b, other_user]
        try:
            assert await device_a.save_memory(
                [types.SimpleNamespace(role="user", content="用户喜欢草莓")],
                "session-strawberry",
            )
            assert "用户喜欢草莓" in await device_b.query_memory("喜欢什么")

            assert await profile_b.save_memory(
                [types.SimpleNamespace(role="user", content="用户喜欢月亮")],
                "session-moon",
            )
            assert await other_user.save_memory(
                [types.SimpleNamespace(role="user", content="用户喜欢松果")],
                "session-pinecone",
            )
            assert "草莓" not in await profile_b.query_memory("喜欢什么")
            assert "草莓" not in await other_user.query_memory("喜欢什么")

            listed = await device_b.list_memory_items()
            assert listed == [{
                "id": listed[0]["id"],
                "content": "用户喜欢草莓",
                "updated_at": listed[0]["updated_at"],
                "source_device_id": "device-a",
                "source_profile_id": "profile-a",
            }]
            memory_id = listed[0]["id"]

            assert await device_b.update_memory_item(memory_id, "用户喜欢蓝莓")
            assert "用户喜欢蓝莓" in await device_a.query_memory("喜欢什么")
            assert await device_b.delete_memory_item(memory_id)
            assert await device_a.list_memory_items() == []

            assert await device_a.save_memory(
                [types.SimpleNamespace(role="user", content="用户住在杭州")],
                "session-hangzhou",
            )
            assert await device_b.clear_memory()
            assert await device_a.list_memory_items() == []
            assert "用户喜欢月亮" in await profile_b.query_memory("喜欢什么")
            assert "用户喜欢松果" in await other_user.query_memory("喜欢什么")
        finally:
            for instance in providers:
                await instance.client.aclose()

        assert core.requests
        for request in core.requests:
            assert request["team_id"]
            assert request["user_id"]
            assert request["agent_id"]
