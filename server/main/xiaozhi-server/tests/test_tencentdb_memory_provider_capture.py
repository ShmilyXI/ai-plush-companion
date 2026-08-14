from datetime import datetime, timezone
import sys
import types
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
sys.modules["config.logger"] = logger_module

from core.providers.memory.tencentdb.client import TencentDbMemoryError
from core.providers.memory.tencentdb.tencentdb import MemoryProvider


def make_provider(*, user_id="7", profile_id="profile-a", device_id="device-a"):
    provider = MemoryProvider({
        "memory_core_url": "http://memory-core:8420",
        "memory_core_api_key": "core-secret",
        "request_timeout_seconds": 4,
    })
    provider.client = AsyncMock()
    provider.init_memory(
        "companion:" + "a" * 64,
        llm=None,
        source_metadata={
            "source_user_id": user_id,
            "source_profile_id": profile_id,
            "source_device_id": device_id,
        },
    )
    return provider


def message(role, content, *, temporary=False):
    return SimpleNamespace(role=role, content=content, is_temporary=temporary)


def assert_utc_timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    assert parsed.tzinfo is not None
    assert parsed.utcoffset() == timezone.utc.utcoffset(parsed)


def test_identity_mapping_separates_users_and_profiles_but_shares_across_devices():
    device_a = make_provider()
    device_b = make_provider(device_id="device-b")
    other_user = make_provider(user_id="8")
    other_profile = make_provider(profile_id="profile-b")

    assert device_a.isolation == {
        "team_id": "ai-plush-companion:user:7",
        "user_id": "7",
        "agent_id": "profile-a",
    }
    assert device_a.task_id == "device-a"
    assert device_b.isolation == device_a.isolation
    assert device_b.task_id == "device-b"
    assert other_user.isolation != device_a.isolation
    assert other_profile.isolation != device_a.isolation


@pytest.mark.asyncio
async def test_save_filters_and_normalizes_messages_with_task_source():
    provider = make_provider()
    provider.client.conversation_add.return_value = {
        "accepted_ids": ["m1", "m2", "m3"],
        "_request_id": "req-add",
    }
    messages = [
        message("system", "prompt"),
        message("tool", "tool output"),
        message("user", "temporary", temporary=True),
        message("user", None),
        message("assistant", "   "),
        message("user", '{"emotion":"happy","content":"  你好  "}'),
        message("assistant", "知道了"),
        message("user", "长" * 9000),
    ]

    assert await provider.save_memory(messages, "session-a") is True

    call = provider.client.conversation_add.await_args
    assert call.args[:2] == (provider.isolation, "session-a")
    assert call.kwargs["task_id"] == "device-a"
    outgoing = call.args[2]
    assert [(item["role"], item["content"]) for item in outgoing] == [
        ("user", "你好"),
        ("assistant", "知道了"),
        ("user", "长" * 8192),
    ]
    for item in outgoing:
        assert set(item) == {"role", "content", "timestamp"}
        assert len(item["content"]) <= 8192
        assert_utc_timestamp(item["timestamp"])
    assert provider.get_diagnostics() == {
        "request_id": "req-add",
        "layer_hits": {},
        "degraded_reason": None,
    }


@pytest.mark.asyncio
async def test_save_requires_session_and_usable_messages_without_raising():
    provider = make_provider()

    assert await provider.save_memory([message("user", "hello")], None) is False
    assert await provider.save_memory([message("system", "prompt")], "session-a") is False

    provider.client.conversation_add.assert_not_awaited()


@pytest.mark.asyncio
async def test_uncertain_write_queries_tail_and_does_not_duplicate_persisted_messages():
    provider = make_provider()
    captured = []

    async def uncertain_add(_isolation, _session_id, outgoing, **_kwargs):
        captured.extend(outgoing)
        raise httpx.ReadTimeout("response lost")

    provider.client.conversation_add.side_effect = uncertain_add
    provider.client.conversation_query.side_effect = lambda *_args, **_kwargs: {
        "messages": list(reversed(captured)),
        "total": len(captured),
        "_request_id": "req-query",
    }

    assert await provider.save_memory(
        [message("user", "你好"), message("assistant", "在呢")],
        "session-a",
    ) is True

    provider.client.conversation_add.assert_awaited_once()
    query = provider.client.conversation_query.await_args
    assert query.args == (provider.isolation,)
    assert query.kwargs == {
        "session_id": "session-a",
        "task_id": "device-a",
        "limit": 2,
        "offset": 0,
    }
    assert provider.get_diagnostics()["request_id"] == "req-query"


@pytest.mark.asyncio
async def test_uncertain_write_retries_once_when_tail_does_not_match():
    provider = make_provider()
    provider.client.conversation_add.side_effect = [
        TencentDbMemoryError("timeout", retryable=True),
        {"accepted_ids": ["m1"], "_request_id": "req-retry"},
    ]
    provider.client.conversation_query.return_value = {
        "messages": [{
            "role": "user",
            "content": "different",
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }],
        "total": 1,
    }

    assert await provider.save_memory([message("user", "hello")], "session-a") is True

    assert provider.client.conversation_add.await_count == 2
    provider.client.conversation_query.assert_awaited_once()


@pytest.mark.asyncio
async def test_definite_client_error_is_not_queried_or_retried():
    provider = make_provider()
    provider.client.conversation_add.side_effect = TencentDbMemoryError(
        "invalid request", status=422, retryable=False
    )

    assert await provider.save_memory([message("user", "hello")], "session-a") is False

    provider.client.conversation_add.assert_awaited_once()
    provider.client.conversation_query.assert_not_awaited()
