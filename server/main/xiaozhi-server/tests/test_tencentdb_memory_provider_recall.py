import asyncio
import sys
import time
import types
from unittest.mock import AsyncMock

import pytest
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
sys.modules["config.logger"] = logger_module

from core.providers.memory.tencentdb.tencentdb import MemoryProvider


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


def configure_success(provider):
    provider.client.atomic_search.return_value = {
        "items": [
            {"content": "用户喜欢草莓"},
            {"content": "用户住在杭州"},
        ],
        "_request_id": "req-l1",
    }
    provider.client.scenario_list.return_value = {
        "entries": [{
            "path": "睡前习惯.md",
            "summary": "通常在晚上十点听海浪声",
        }],
        "_request_id": "req-l2",
    }
    provider.client.core_read.return_value = {
        "content": "用户偏好温和简短的回应。",
        "_request_id": "req-l3",
    }


@pytest.mark.asyncio
async def test_combines_l1_l2_l3_without_device_filter_or_legacy_recall():
    provider = make_provider()
    configure_success(provider)

    result = await provider.query_memory("我喜欢什么")

    assert result == (
        "[原子记忆]\n"
        "- 用户喜欢草莓\n"
        "- 用户住在杭州\n\n"
        "[相关场景]\n"
        "- 睡前习惯：通常在晚上十点听海浪声\n\n"
        "[用户画像]\n"
        "用户偏好温和简短的回应。"
    )
    provider.client.atomic_search.assert_awaited_once_with(
        provider.isolation, "我喜欢什么", limit=8
    )
    provider.client.scenario_list.assert_awaited_once_with(provider.isolation)
    provider.client.core_read.assert_awaited_once_with(provider.isolation)
    for method in (
        provider.client.atomic_search,
        provider.client.scenario_list,
        provider.client.core_read,
    ):
        assert "task_id" not in method.await_args.kwargs
    assert all(call[0] != "recall" for call in provider.client.mock_calls)
    assert provider.get_diagnostics() == {
        "request_id": "req-l1",
        "recall_strategy": "atomic_hybrid+scenario_navigation+core",
        "layer_hits": {"L1": 2, "L2": 1, "L3": 1},
        "degraded_reason": None,
    }


@pytest.mark.asyncio
async def test_recall_enforces_entry_layer_and_final_budgets():
    provider = make_provider()
    provider.client.atomic_search.return_value = {
        "items": [
            {"content": "重复"},
            {"content": "重复"},
            {"content": ""},
            {"broken": "ignored"},
            *({"content": f"原子{index}-" + "甲" * 600} for index in range(12)),
        ]
    }
    provider.client.scenario_list.return_value = {
        "entries": [
            {"path": "目录/", "summary": "ignored"},
            *(
                {"path": f"场景{index}.md", "summary": "乙" * 500}
                for index in range(8)
            ),
        ]
    }
    provider.client.core_read.return_value = {"content": "丙" * 2500}

    result = await provider.query_memory("预算")

    assert len(result) <= 6000
    assert result.count("- 重复") == 1
    assert result.count("- 原子") <= 7
    assert result.count("- 场景") <= 5
    assert "目录" not in result
    assert provider.get_diagnostics()["layer_hits"] == {"L1": 6, "L2": 3, "L3": 1}


@pytest.mark.asyncio
@pytest.mark.parametrize("failed_layer", ["L1", "L2", "L3"])
@pytest.mark.parametrize("failure_kind", ["error", "timeout"])
async def test_one_failed_layer_keeps_the_other_layers(
    monkeypatch, failed_layer, failure_kind
):
    provider = make_provider()
    configure_success(provider)
    monkeypatch.setattr(
        "core.providers.memory.tencentdb.tencentdb.LAYER_TIMEOUT_SECONDS", 0.01
    )

    async def fail_or_wait(*_args, **_kwargs):
        if failure_kind == "timeout":
            await asyncio.sleep(0.1)
            return {}
        raise RuntimeError("unavailable")

    method = {
        "L1": provider.client.atomic_search,
        "L2": provider.client.scenario_list,
        "L3": provider.client.core_read,
    }[failed_layer]
    method.side_effect = fail_or_wait

    result = await provider.query_memory("部分失败")

    assert result
    assert f"[{ {'L1': '原子记忆', 'L2': '相关场景', 'L3': '用户画像'}[failed_layer] }]" not in result
    diagnostics = provider.get_diagnostics()
    assert diagnostics["layer_hits"][failed_layer] == 0
    expected_class = "TimeoutError" if failure_kind == "timeout" else "RuntimeError"
    assert diagnostics["degraded_reason"] == f"{failed_layer}:{expected_class}"


@pytest.mark.asyncio
async def test_all_layer_failures_return_empty_within_provider_deadline(monkeypatch):
    provider = make_provider()
    monkeypatch.setattr(
        "core.providers.memory.tencentdb.tencentdb.LAYER_TIMEOUT_SECONDS", 0.01
    )

    async def wait_forever(*_args, **_kwargs):
        await asyncio.sleep(1)

    provider.client.atomic_search.side_effect = wait_forever
    provider.client.scenario_list.side_effect = RuntimeError("scenario unavailable")
    provider.client.core_read.side_effect = wait_forever

    started = time.monotonic()
    result = await provider.query_memory("全部失败")

    assert result == ""
    assert time.monotonic() - started < 0.2
    assert provider.get_diagnostics() == {
        "request_id": None,
        "recall_strategy": "atomic_hybrid+scenario_navigation+core",
        "layer_hits": {"L1": 0, "L2": 0, "L3": 0},
        "degraded_reason": "L1:TimeoutError,L2:RuntimeError,L3:TimeoutError",
    }


@pytest.mark.asyncio
async def test_absent_core_file_is_empty_not_degraded():
    provider = make_provider()
    provider.client.atomic_search.return_value = {"items": []}
    provider.client.scenario_list.return_value = {"entries": []}
    provider.client.core_read.return_value = {}

    assert await provider.query_memory("没有记忆") == ""
    assert provider.get_diagnostics()["degraded_reason"] is None
