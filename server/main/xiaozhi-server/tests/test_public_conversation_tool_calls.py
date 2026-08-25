import json
import time
from types import SimpleNamespace

import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession
from core.public_conversation.tool_calls import PublicToolCallAccumulator, PublicToolCallError
from core.public_conversation.tools import PublicToolError, PublicToolResult


def claims():
    now = int(time.time())
    return RuntimeTokenClaims(
        "conversation-a", "user-a", "agent-a", 4,
        ("conversation:text", "conversation:audio"),
        ("text", "audio"), ("text", "audio"), now - 1, now + 900,
    )


def tool_call(call_id="call-1", name="get_weather", arguments='{"location":"深圳"}'):
    return SimpleNamespace(
        index=0,
        id=call_id,
        function=SimpleNamespace(name=name, arguments=arguments),
    )


class ToolCallingLlm:
    def response_with_functions(self, _session_id, dialogue, functions=None, **_kwargs):
        assert functions[0]["function"]["name"] == "get_weather"
        if not any(message.get("role") == "tool" for message in dialogue):
            yield None, [tool_call()]
        else:
            if "实时工具调用失败" in dialogue[-1]["content"]:
                yield "天气服务暂时不可用，请稍后再试。", None
            else:
                assert "深圳" in dialogue[-1]["content"]
                yield "深圳当前 28 度。", None


class FakeTts:
    def to_playground_wav(self, text):
        return b"wav:" + text.encode()


class FakeToolRuntime:
    def __init__(self, *, error=None):
        self.error = error
        self.turn = SimpleNamespace(
            skill=SimpleNamespace(execution_prompt="调用天气工具，不要编造。", timeout_ms=1000),
            allowed_tool_names=frozenset({"get_weather"}),
        )

    async def select(self, _text):
        return self.turn

    def schemas(self, _turn):
        return [{
            "type": "function",
            "function": {"name": "get_weather", "description": "查询天气",
                         "parameters": {"type": "object", "properties": {}}},
        }]

    async def execute(self, _turn, name, arguments, **_kwargs):
        assert name == "get_weather"
        assert arguments == {"location": "深圳"}
        if self.error:
            raise self.error
        return PublicToolResult("get_weather", "深圳当前 28 度，晴", 25)


def session(runtime, llm=None):
    return PublicConversationSession(
        claims(),
        {"config": {"systemPrompt": "测试角色"}, "runtime_models": {"LLM": {}, "TTS": {}}},
        llm_factory=lambda _model: llm or ToolCallingLlm(),
        tts_factory=lambda _model: FakeTts(),
        tool_runtime_factory=lambda _bundle: runtime,
    )


def test_merges_streamed_tool_call_fragments_and_requires_json_object():
    accumulator = PublicToolCallAccumulator()
    accumulator.add([tool_call(arguments='{"location":')])
    accumulator.add([tool_call(call_id=None, name=None, arguments='"深圳"}')])
    calls = accumulator.finish()
    assert calls[0].id == "call-1"
    assert calls[0].name == "get_weather"
    assert calls[0].arguments == {"location": "深圳"}

    invalid = PublicToolCallAccumulator()
    invalid.add([tool_call(arguments="[]")])
    with pytest.raises(PublicToolCallError, match="object"):
        invalid.finish()


@pytest.mark.asyncio
async def test_weather_tool_events_precede_grounded_reply():
    events = await session(FakeToolRuntime()).handle_text(TextTurnInput("r1", "深圳天气怎么样"))

    assert [item.event_type for item in events] == [
        "turn.started", "tool.started", "tool.completed",
        "llm.delta", "tts.audio", "turn.completed",
    ]
    assert events[1].details == {"name": "get_weather"}
    assert events[2].details == {"name": "get_weather", "duration_ms": 25}
    assert events[-1].details["text"] == "深圳当前 28 度。"


@pytest.mark.asyncio
async def test_tool_failure_is_reported_and_llm_receives_unavailable_context():
    runtime = FakeToolRuntime(error=PublicToolError("timeout", "tool execution timed out"))
    events = await session(runtime).handle_text(TextTurnInput("r1", "深圳天气怎么样"))

    failed = next(item for item in events if item.event_type == "tool.failed")
    assert failed.details == {
        "name": "get_weather", "code": "timeout", "message": "实时查询超时，请稍后再试",
    }
    assert events[-1].event_type == "turn.completed"
    assert "timed out" not in json.dumps(failed.to_dict(), ensure_ascii=False)


@pytest.mark.asyncio
async def test_provider_without_function_calling_never_fabricates_live_data():
    class TextOnlyLlm:
        def response(self, _session_id, _dialogue):
            yield "我猜现在天气不错"

    events = await session(FakeToolRuntime(), TextOnlyLlm()).handle_text(
        TextTurnInput("r1", "深圳天气怎么样")
    )

    assert [item.event_type for item in events] == [
        "turn.started", "tool.failed", "llm.delta", "tts.audio", "turn.completed",
    ]
    assert events[1].details["code"] == "provider_unsupported"
    assert "暂时无法获取实时天气或新闻" in events[-1].details["text"]
    assert "天气不错" not in events[-1].details["text"]
