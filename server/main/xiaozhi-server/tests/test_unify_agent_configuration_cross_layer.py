"""Simulated cross-layer acceptance for the unify-agent-configuration change.

The manager API and provider are fakes in this test. It proves the contract and
isolation rules without claiming a live MQTT, database, or hardware run.
"""

import sys
import types

import pytest

from loguru import logger

logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
sys.modules.setdefault("config.logger", logger_module)

from core.capabilities.cache import CapabilityBundleCache
from core.capabilities.client import CapabilityBundleClient
from core.providers.memory.mem_local_short.mem_local_short import MemoryProvider


class PublishedAgentManager:
    def __init__(self):
        self.active_version = 7

    async def publish(self, draft):
        self.active_version += 1
        return {"agentId": draft["agentId"], "activeVersion": self.active_version}

    async def bundle(self, device_id):
        tool_name = {
            "device-a": "self_screen_set_brightness",
            "device-b": "self_camera_take_photo",
        }[device_id]
        return {
            "agentId": "agent-a",
            "agentVersionNo": self.active_version,
            "configVersion": self.active_version,
            "deviceId": device_id,
            "skills": [],
            "tools": {tool_name: {"name": tool_name, "type": "DEVICE_TOOL", "refId": tool_name,
                                   "alias": None, "purpose": tool_name, "required": False,
                                   "defaults": {}, "runtime": {}}},
        }


@pytest.mark.asyncio
async def test_simulated_console_publish_to_runtime_keeps_two_devices_isolated():
    manager = PublishedAgentManager()
    published = await manager.publish({"agentId": "agent-a", "prompt": "陪伴"})
    cache = CapabilityBundleCache(CapabilityBundleClient(manager.bundle))

    first = await cache.get("device-a", force_refresh=True)
    second = await cache.get("device-b", force_refresh=True)

    assert published["activeVersion"] == 8
    assert first.agent_version_no == second.agent_version_no == 8
    assert set(first.tools) == {"self_screen_set_brightness"}
    assert set(second.tools) == {"self_camera_take_photo"}
    assert "self_camera_take_photo" not in first.tools
    assert "self_screen_set_brightness" not in second.tools


@pytest.mark.asyncio
async def test_simulated_manager_to_memory_provider_merge_overwrite_and_retry():
    source = MemoryProvider({}, None)
    target = MemoryProvider({}, None)
    source.init_memory("companion:source", llm=None, save_to_file=False)
    target.init_memory("companion:target", llm=None, save_to_file=False)
    await source.replace_memory_items(["喜欢草莓", "住在杭州"])
    await target.replace_memory_items(["喜欢草莓", "目标独有"])

    source_items = await source.list_memory_items()
    target_items = await target.list_memory_items()
    target_by_content = {item["content"]: item for item in target_items}
    for item in source_items:
        if item["content"] not in target_by_content:
            target_by_content[item["content"]] = item
    await target.replace_memory_items(list(target_by_content))
    assert {item["content"] for item in await target.list_memory_items()} == {"喜欢草莓", "目标独有", "住在杭州"}
    assert {item["content"] for item in await source.list_memory_items()} == {"喜欢草莓", "住在杭州"}

    backup = list(await target.list_memory_items())
    try:
        raise RuntimeError("simulated provider failure")
    except RuntimeError:
        await target.replace_memory_items([item["content"] for item in backup])
    assert {item["content"] for item in await target.list_memory_items()} == {"喜欢草莓", "目标独有", "住在杭州"}
