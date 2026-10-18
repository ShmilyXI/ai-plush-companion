import asyncio
from types import SimpleNamespace

import pytest

from plugins_func.register import Action, ActionResponse, ToolType
from core.public_conversation.tools import PublicConversationToolRuntime, PublicToolError


def bundle(tool_name="get_weather", plugin_id="plugin-weather", keyword="天气", defaults=None):
    schema = {
        "type": "function",
        "function": {
            "name": tool_name,
            "description": "只读查询",
            "parameters": {
                "type": "object",
                "properties": {
                    "location": {"type": "string"},
                    "lang": {"type": "string"},
                },
            },
        },
    }
    return {
        "conversation_id": "conversation-a",
        "config": {
            "skills": [{
                "id": "skill-weather", "version": 2, "packageVersion": 2,
                "packageSha256": "a" * 64, "name": "天气查询",
                "description": "查询天气", "executionPrompt": "必须调用工具，不要编造。",
                "semanticThreshold": 0.7, "responseMode": "LLM", "timeoutMs": 1000,
                "failureMessage": "天气服务暂时不可用", "bindingPriority": 10,
                "triggers": [{"type": "KEYWORD", "value": keyword, "priority": 10,
                              "caseSensitive": False, "enabled": True}],
                "toolNames": [tool_name], "defaults": {},
            }],
            "tools": {
                tool_name: {
                    "name": tool_name, "type": "PLUGIN", "refId": plugin_id,
                    "required": True, "defaults": defaults or {},
                    "runtime": {"executor": "SERVER_PLUGIN", "schema": schema},
                },
            },
        },
    }


def plugin_item(name, function):
    return SimpleNamespace(name=name, description={}, func=function, type=ToolType.SYSTEM_CTL)


@pytest.mark.asyncio
async def test_selects_and_executes_only_bound_weather_tool():
    async def weather(_conn, location=None, lang="zh_CN"):
        return ActionResponse(Action.REQLLM, f"{location} 当前 28 度，语言 {lang}", None)

    runtime = PublicConversationToolRuntime(
        bundle(defaults={"lang": "zh_CN"}),
        plugin_registry={"get_weather": plugin_item("get_weather", weather)},
    )

    turn = await runtime.select("深圳今天的天气怎么样")
    assert [schema["function"]["name"] for schema in runtime.schemas(turn)] == ["get_weather"]
    result = await runtime.execute(turn, "get_weather", {"location": "深圳"})

    assert "深圳" in result.content
    assert result.name == "get_weather"
    assert result.duration_ms >= 0


@pytest.mark.asyncio
async def test_rejects_unbound_or_non_whitelisted_tool():
    async def search(_conn, query=None):
        return ActionResponse(Action.REQLLM, query, None)

    runtime = PublicConversationToolRuntime(
        bundle("web_search", "plugin-web-search", "搜索"),
        plugin_registry={"web_search": plugin_item("web_search", search)},
    )
    turn = await runtime.select("搜索最近新闻")

    assert runtime.schemas(turn) == []
    with pytest.raises(PublicToolError, match="not allowed") as captured:
        await runtime.execute(turn, "web_search", {"query": "x"})
    assert captured.value.code == "not_allowed"


@pytest.mark.asyncio
async def test_validates_arguments_before_plugin_execution():
    called = False

    async def weather(_conn, **_kwargs):
        nonlocal called
        called = True
        return ActionResponse(Action.REQLLM, "unexpected", None)

    configured = bundle()
    configured["config"]["tools"]["get_weather"]["runtime"]["schema"]["function"]["parameters"]["required"] = ["location"]
    runtime = PublicConversationToolRuntime(
        configured, plugin_registry={"get_weather": plugin_item("get_weather", weather)}
    )

    with pytest.raises(PublicToolError) as captured:
        await runtime.execute(await runtime.select("天气"), "get_weather", {"location": 7})
    assert captured.value.code == "invalid_arguments"
    assert called is False


@pytest.mark.asyncio
async def test_timeout_and_oversized_result_use_stable_codes():
    async def slow(_conn, **_kwargs):
        await asyncio.sleep(0.05)
        return ActionResponse(Action.REQLLM, "ok", None)

    runtime = PublicConversationToolRuntime(
        bundle(), plugin_registry={"get_weather": plugin_item("get_weather", slow)}
    )
    turn = await runtime.select("天气")
    with pytest.raises(PublicToolError) as timeout:
        await runtime.execute(turn, "get_weather", {}, timeout_seconds=0.001)
    assert timeout.value.code == "timeout"

    async def huge(_conn, **_kwargs):
        return ActionResponse(Action.REQLLM, "x" * 32_001, None)

    runtime = PublicConversationToolRuntime(
        bundle(), plugin_registry={"get_weather": plugin_item("get_weather", huge)}
    )
    with pytest.raises(PublicToolError) as oversized:
        await runtime.execute(await runtime.select("天气"), "get_weather", {})
    assert oversized.value.code == "result_too_large"
