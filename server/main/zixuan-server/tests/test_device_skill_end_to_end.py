import ast
import asyncio
import hashlib
import json
import threading
from enum import Enum
from pathlib import Path
from typing import Any, Dict

import pytest

from core.capabilities.cache import CapabilityBundleCache
from core.capabilities.client import CapabilityBundleClient
from core.capabilities.runtime import SkillTurnRuntime
from core.providers.tools.server_mcp.config_resolver import (
    BackendMCPConfigResolver,
    filter_discovered_tools,
)
ROOT = Path(__file__).parents[1]
CONNECTION_PATH = ROOT / "core/connection.py"
DEVICE_MCP_EXECUTOR_PATH = (
    ROOT / "core/providers/tools/device_mcp/mcp_executor.py"
)


class Action(Enum):
    ERROR = -1
    NOTFOUND = 0
    REQLLM = 3


class ActionResponse:
    def __init__(self, action, result=None, response=None):
        self.action = action
        self.result = result
        self.response = response


def schema_hash(schema):
    encoded = json.dumps(
        schema, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def capability_bundle(
    device_id,
    location,
    *,
    config_version=1,
    include_weather=True,
    include_brightness=True,
):
    mcp_schema = {
        "type": "object",
        "properties": {"query": {"type": "string"}},
        "required": ["query"],
    }
    skills = [
        {
            "id": "skill-news",
            "version": 1,
            "name": "新闻",
            "description": "查询新闻",
            "executionPrompt": "查询真实新闻后回答。",
            "semanticThreshold": 0.7,
            "responseMode": "LLM",
            "timeoutMs": 30000,
            "failureMessage": "新闻暂时不可用。",
            "bindingPriority": 20,
            "triggers": [
                {
                    "type": "POSITIVE_EXAMPLE",
                    "value": "今天发生了什么",
                    "priority": 50,
                    "caseSensitive": False,
                    "enabled": True,
                }
            ],
            "toolNames": ["get_news"],
            "defaults": {},
        },
        {
            "id": "skill-search",
            "version": 1,
            "name": "搜索",
            "description": "搜索资料",
            "executionPrompt": "搜索可靠资料后回答。",
            "semanticThreshold": 0.7,
            "responseMode": "LLM",
            "timeoutMs": 30000,
            "failureMessage": "搜索暂时不可用。",
            "bindingPriority": 10,
            "triggers": [
                {
                    "type": "POSITIVE_EXAMPLE",
                    "value": "帮我找资料",
                    "priority": 50,
                    "caseSensitive": False,
                    "enabled": True,
                }
            ],
            "toolNames": ["mcp_search"],
            "defaults": {},
        },
    ]
    tools = {
        "get_news": {
            "name": "get_news",
            "type": "PLUGIN",
            "refId": "plugin-news",
            "alias": None,
            "purpose": "查询新闻",
            "required": True,
            "defaults": {},
            "runtime": {},
        },
        "mcp_search": {
            "name": "mcp_search",
            "type": "MCP",
            "refId": "mcp-search-snapshot",
            "alias": None,
            "purpose": "搜索资料",
            "required": True,
            "defaults": {},
            "runtime": {
                "serverId": "server-search",
                "transport": "SSE",
                "connectionConfig": {
                    "url": "https://mcp.example.test/sse",
                    "headers": {},
                },
                "secretRefs": {},
                "approvedCommandTemplate": None,
                "inputSchema": mcp_schema,
                "schemaSha256": schema_hash(mcp_schema),
            },
        },
        "self_screen_set_brightness": {
            "name": "self_screen_set_brightness",
            "type": "DEVICE_TOOL",
            "refId": "device-screen-brightness",
            "alias": None,
            "purpose": "调节屏幕亮度",
            "required": False,
            "defaults": {},
            "runtime": {},
        } if include_brightness else {},
    }
    if not include_brightness:
        tools.pop("self_screen_set_brightness", None)
    if include_weather:
        skills.insert(
            0,
            {
                "id": "skill-weather",
                "version": 1,
                "name": "天气",
                "description": "查询天气",
                "executionPrompt": "按设备默认地点查询真实天气。",
                "semanticThreshold": 0.7,
                "responseMode": "LLM",
                "timeoutMs": 30000,
                "failureMessage": "天气暂时不可用。",
                "bindingPriority": 30,
                "triggers": [
                    {
                        "type": "KEYWORD",
                        "value": "天气",
                        "priority": 100,
                        "caseSensitive": False,
                        "enabled": True,
                    }
                ],
                "toolNames": ["get_weather"],
                "defaults": {},
            },
        )
        tools["get_weather"] = {
            "name": "get_weather",
            "type": "PLUGIN",
            "refId": "plugin-weather",
            "alias": None,
            "purpose": "查询天气",
            "required": True,
            "defaults": {"location": location},
            "runtime": {},
        }
    return {
        "deviceId": device_id,
        "configVersion": config_version,
        "skills": skills,
        "tools": tools,
    }


class FakeManagerAPI:
    def __init__(self):
        self.bundles = {
            "device-a": capability_bundle("device-a", "杭州"),
            "device-b": capability_bundle("device-b", "上海", include_brightness=False),
        }

    async def fetch(self, device_id):
        return self.bundles[device_id]


class SemanticClassifier:
    async def classify(self, utterance, candidates):
        target = "skill-news" if "发生了什么" in utterance else "skill-search"
        return next(skill for skill in candidates if skill.id == target)


class RuleOnlyClassifier:
    async def classify(self, utterance, candidates):
        raise AssertionError("天气规则命中不应调用语义分类")


@pytest.mark.asyncio
async def test_two_devices_share_weather_skill_but_keep_device_defaults_and_role_changes():
    manager = FakeManagerAPI()
    cache = CapabilityBundleCache(CapabilityBundleClient(manager.fetch))
    runtime = SkillTurnRuntime()
    function = {
        "type": "function",
        "function": {
            "name": "get_weather",
            "parameters": {"properties": {"location": {"type": "string"}}},
        },
    }

    first = await cache.get("device-a", force_refresh=True)
    second = await cache.get("device-b", force_refresh=True)
    first_turn = await runtime.select(first, "今天天气怎么样", RuleOnlyClassifier())
    second_turn = await runtime.select(second, "今天天气怎么样", RuleOnlyClassifier())
    runtime.set_role_metadata({"agentId": "another-role"})
    after_role_change = await runtime.select(
        first, "今天天气怎么样", RuleOnlyClassifier()
    )

    assert first_turn.skill.id == second_turn.skill.id == "skill-weather"
    assert runtime.prepare_tool_call(
        first.tools["get_weather"], {}, function
    ).arguments == {"location": "杭州"}
    assert runtime.prepare_tool_call(
        second.tools["get_weather"], {}, function
    ).arguments == {"location": "上海"}
    assert "self_screen_set_brightness" in first.tools
    assert "self_screen_set_brightness" not in second.tools
    assert after_role_change.bundle.device_id == "device-a"
    assert after_role_change.allowed_tool_names == first_turn.allowed_tool_names


@pytest.mark.asyncio
async def test_news_and_search_use_backend_skill_routing_without_legacy_keywords():
    manager = FakeManagerAPI()
    bundle = await CapabilityBundleClient(manager.fetch).fetch("device-a")
    runtime = SkillTurnRuntime()
    classifier = SemanticClassifier()

    news = await runtime.select(bundle, "给我讲讲今天发生了什么", classifier)
    search = await runtime.select(bundle, "帮忙找一下量子计算资料", classifier)

    assert news.skill.id == "skill-news"
    assert news.trigger_mode == "SEMANTIC"
    assert news.allowed_tool_names == frozenset({"handle_exit_intent", "get_news"})
    assert search.skill.id == "skill-search"
    assert search.trigger_mode == "SEMANTIC"
    assert search.allowed_tool_names == frozenset(
        {"handle_exit_intent", "mcp_search"}
    )


def load_connection_methods(namespace):
    tree = ast.parse(CONNECTION_PATH.read_text(encoding="utf-8"))
    connection = next(
        node
        for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == "ConnectionHandler"
    )
    methods = [
        node
        for node in connection.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name in {"_set_capability_bundle", "_refresh_capability_bundle_for_turn"}
    ]
    exec(
        compile(
            ast.fix_missing_locations(ast.Module(body=methods, type_ignores=[])),
            str(CONNECTION_PATH),
            "exec",
        ),
        namespace,
    )
    return namespace


def load_device_mcp_execute():
    tree = ast.parse(DEVICE_MCP_EXECUTOR_PATH.read_text(encoding="utf-8"))
    executor = next(
        node
        for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == "DeviceMCPExecutor"
    )
    method = next(
        node
        for node in executor.body
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "execute"
    )

    async def call_mcp_tool(*_):
        raise AssertionError("离线设备不应发起工具调用")

    namespace = {
        "Action": Action,
        "ActionResponse": ActionResponse,
        "Any": Any,
        "Dict": Dict,
        "call_mcp_tool": call_mcp_tool,
    }
    exec(
        compile(
            ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[])),
            str(DEVICE_MCP_EXECUTOR_PATH),
            "exec",
        ),
        namespace,
    )
    return namespace["execute"]


def test_unbinding_refreshes_the_device_bundle_before_the_next_turn():
    manager = FakeManagerAPI()
    cache = CapabilityBundleCache(CapabilityBundleClient(manager.fetch))
    loop = asyncio.new_event_loop()
    thread = threading.Thread(target=loop.run_forever)
    thread.start()
    try:
        initial = asyncio.run_coroutine_threadsafe(
            cache.get("device-a", force_refresh=True), loop
        ).result(timeout=2)
        manager.bundles["device-a"] = capability_bundle(
            "device-a", "杭州", config_version=2, include_weather=False
        )
        namespace = load_connection_methods(
            {"asyncio": asyncio, "_CAPABILITY_BUNDLE_CACHE": cache}
        )
        connection_type = type(
            "Connection",
            (),
            {
                "_set_capability_bundle": namespace["_set_capability_bundle"],
                "_refresh_capability_bundle_for_turn": namespace[
                    "_refresh_capability_bundle_for_turn"
                ],
            },
        )
        connection = connection_type()
        connection.read_config_from_api = True
        connection.device_id = "device-a"
        connection.capability_bundle = initial
        connection.func_handler = None
        connection.loop = loop

        refreshed = connection._refresh_capability_bundle_for_turn()

        assert refreshed.config_version == 2
        assert "get_weather" not in refreshed.tools
    finally:
        loop.call_soon_threadsafe(loop.stop)
        thread.join(timeout=2)
        loop.close()


@pytest.mark.asyncio
async def test_mcp_whitelist_rejects_new_tools_and_offline_device_tools_fail():
    manager = FakeManagerAPI()
    bundle = await CapabilityBundleClient(manager.fetch).fetch("device-a")
    servers = await BackendMCPConfigResolver(lambda *_: None).resolve(bundle)
    approved_schema = bundle.tools["mcp_search"].runtime["inputSchema"]

    filtered = filter_discovered_tools(
        servers[0],
        [
            {"name": "mcp_search", "inputSchema": approved_schema},
            {"name": "mcp_unapproved", "inputSchema": {"type": "object"}},
        ],
    )

    class OfflineClient:
        async def is_ready(self):
            return False

    connection = type("Connection", (), {"mcp_client": OfflineClient()})()
    executor = type("Executor", (), {})()
    response = await load_device_mcp_execute()(
        executor, connection, "self_screen_set_brightness", {"brightness": 80}
    )

    assert [tool["name"] for tool in filtered] == ["mcp_search"]
    assert response.action == Action.ERROR
    assert response.response == "设备端MCP客户端未准备就绪"
