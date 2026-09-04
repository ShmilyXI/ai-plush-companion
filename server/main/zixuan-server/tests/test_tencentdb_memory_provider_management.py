import sys
import types
from unittest.mock import AsyncMock, call

import pytest
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
sys.modules["config.logger"] = logger_module

from core.providers.memory.tencentdb.tencentdb import MemoryProvider
from core.providers.memory.tencentdb.client import TencentDbMemoryError


def make_provider():
    provider = MemoryProvider({
        "memory_core_url": "http://memory-core:8420",
        "memory_core_api_key": "core-secret",
    })
    provider.client = AsyncMock()
    provider.init_memory(
        "companion:" + "a" * 64,
        llm=None,
        source_metadata={
            "source_user_id": 7,
            "source_profile_id": "profile-a",
            "source_device_id": "device-b",
        },
    )
    return provider


def atomic_item(index, *, updated_at=None, task_id=None, agent_id="profile-a"):
    return {
        "id": f"atomic-{index}",
        "content": f"记忆 {index}",
        "updated_at": updated_at or f"2026-08-14T10:{index % 60:02d}:00Z",
        "task_id": task_id or f"device-{index}",
        "agent_id": agent_id,
    }


@pytest.mark.asyncio
async def test_list_pages_shared_atomic_memory_and_sorts_newest_first():
    provider = make_provider()
    first_page = [atomic_item(index) for index in range(100)]
    provider.client.atomic_query.side_effect = [
        {"items": first_page, "total": 101},
        {
            "items": [{
                "id": "atomic-newest",
                "content": "用户喜欢草莓",
                "updated_at": "2026-08-14T11:00:00Z",
                "task_id": "device-a",
                "agent_id": "profile-a",
            }],
            "total": 101,
        },
    ]

    items = await provider.list_memory_items()

    assert len(items) == 101
    assert items[0] == {
        "id": "atomic-newest",
        "content": "用户喜欢草莓",
        "updated_at": "2026-08-14T11:00:00Z",
        "source_device_id": "device-a",
        "source_profile_id": "profile-a",
    }
    assert provider.client.atomic_query.await_args_list == [
        call(provider.isolation, limit=100, offset=0),
        call(provider.isolation, limit=100, offset=100),
    ]
    assert all("task_id" not in item.kwargs for item in provider.client.atomic_query.await_args_list)


@pytest.mark.asyncio
async def test_list_raises_when_memory_core_is_unavailable():
    provider = make_provider()
    provider.client.atomic_query.side_effect = RuntimeError("offline")

    with pytest.raises(RuntimeError, match="memory provider list failed"):
        await provider.list_memory_items()


@pytest.mark.asyncio
async def test_update_and_delete_require_owned_atomic_id_without_device_filter():
    provider = make_provider()
    provider.client.atomic_query.return_value = {
        "items": [atomic_item(1), atomic_item(2)],
        "total": 2,
    }
    provider.client.atomic_update.return_value = {"id": "atomic-1"}
    provider.client.atomic_delete.return_value = {"deleted_count": 1}

    assert await provider.update_memory_item("atomic-1", "  新内容  ") is True
    assert await provider.delete_memory_item("atomic-2") is True
    assert await provider.update_memory_item("foreign", "内容") is False
    assert await provider.delete_memory_item("foreign") is False
    assert await provider.update_memory_item("atomic-1", "   ") is False

    provider.client.atomic_update.assert_awaited_once_with(
        provider.isolation, "atomic-1", "新内容"
    )
    provider.client.atomic_delete.assert_awaited_once_with(
        provider.isolation, ["atomic-2"]
    )
    assert provider.client.atomic_query.await_count == 4
    for query_call in provider.client.atomic_query.await_args_list:
        assert "task_id" not in query_call.kwargs


@pytest.mark.asyncio
async def test_full_clear_deletes_all_layers_in_bounded_chunks_and_core_last():
    provider = make_provider()
    operations = []
    l0_first = [{"id": f"message-{index}"} for index in range(100)]
    l1_first = [atomic_item(index) for index in range(100)]
    provider.client.conversation_query.side_effect = [
        {"messages": l0_first, "total": 101},
        {"messages": [{"id": "message-100"}], "total": 101},
    ]
    provider.client.atomic_query.side_effect = [
        {"items": l1_first, "total": 101},
        {"items": [atomic_item(100)], "total": 101},
    ]
    provider.client.scenario_list.return_value = {
        "entries": [
            {"path": "目录/"},
            {"path": "场景/a.md"},
            {"path": "场景/b.md"},
        ]
    }
    provider.client.core_read.return_value = {"content": "现有画像"}

    async def conversation_delete(*_args, **kwargs):
        operations.append(("l0", kwargs["message_ids"]))
        return {"deleted_count": len(kwargs["message_ids"])}

    async def atomic_delete(_isolation, ids):
        operations.append(("l1", ids))
        return {"deleted_count": len(ids)}

    async def scenario_remove(_isolation, paths):
        operations.append(("l2", paths))
        return {"removed": paths}

    async def core_write(_isolation, content):
        operations.append(("l3", content))
        return {"version": "v2"}

    provider.client.conversation_delete.side_effect = conversation_delete
    provider.client.atomic_delete.side_effect = atomic_delete
    provider.client.scenario_remove.side_effect = scenario_remove
    provider.client.core_write.side_effect = core_write

    assert await provider.clear_memory() is True

    assert [len(item[1]) for item in operations if item[0] == "l0"] == [100, 1]
    assert [len(item[1]) for item in operations if item[0] == "l1"] == [100, 1]
    assert [item[1] for item in operations if item[0] == "l2"] == [
        ["场景/a.md"],
        ["场景/b.md"],
    ]
    assert operations[-1] == ("l3", "")
    assert provider.client.conversation_query.await_args_list == [
        call(provider.isolation, limit=100, offset=0),
        call(provider.isolation, limit=100, offset=100),
    ]
    assert all("task_id" not in item.kwargs for item in provider.client.conversation_query.await_args_list)


@pytest.mark.asyncio
async def test_clear_can_resume_after_l2_failure_without_resurrecting_deleted_layers():
    provider = make_provider()
    provider.client.conversation_query.return_value = {
        "messages": [{"id": "message-1"}],
        "total": 1,
    }
    provider.client.atomic_query.return_value = {
        "items": [atomic_item(1)],
        "total": 1,
    }
    provider.client.scenario_list.return_value = {
        "entries": [{"path": "场景/a.md"}]
    }
    provider.client.scenario_remove.side_effect = RuntimeError("storage offline")

    assert await provider.clear_memory() is False
    provider.client.core_write.assert_not_awaited()

    provider.client.conversation_query.return_value = {"messages": [], "total": 0}
    provider.client.atomic_query.return_value = {"items": [], "total": 0}
    provider.client.scenario_remove.side_effect = None
    provider.client.scenario_remove.return_value = {"removed": ["场景/a.md"]}
    provider.client.core_read.return_value = {"content": "现有画像"}
    provider.client.core_write.return_value = {"version": "v2"}

    assert await provider.clear_memory() is True
    provider.client.core_write.assert_awaited_once_with(provider.isolation, "")


@pytest.mark.asyncio
async def test_clear_treats_absent_core_profile_as_already_empty():
    provider = make_provider()
    provider.client.conversation_query.return_value = {"messages": [], "total": 0}
    provider.client.atomic_query.return_value = {"items": [], "total": 0}
    provider.client.scenario_list.return_value = {"entries": []}
    provider.client.core_read.side_effect = TencentDbMemoryError(
        "not found", status=200, code=404
    )

    assert await provider.clear_memory() is True
    provider.client.core_write.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("layer", ["L0", "L1"])
async def test_clear_rejects_more_than_ten_thousand_records(layer):
    provider = make_provider()
    provider.client.conversation_query.return_value = {
        "messages": [],
        "total": 10001 if layer == "L0" else 0,
    }
    provider.client.atomic_query.return_value = {
        "items": [],
        "total": 10001 if layer == "L1" else 0,
    }

    assert await provider.clear_memory() is False
    provider.client.conversation_delete.assert_not_awaited()
    provider.client.atomic_delete.assert_not_awaited()
