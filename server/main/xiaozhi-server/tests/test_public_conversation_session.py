import base64
import time

import pytest

from core.public_conversation.protocol import AudioTurnInput, RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession


def claims():
    now = int(time.time())
    return RuntimeTokenClaims(
        "conversation-a", "user-a", "agent-a", 4,
        ("conversation:text", "conversation:audio"), ("text", "audio"), ("text", "audio"),
        now - 1, now + 900,
    )


class FakeLlm:
    tools_enabled = False

    def response(self, _session_id, _dialogue):
        assert all(message.get("role") in {"system", "user"} for message in _dialogue)
        assert all("tools" not in message and "mcp" not in message for message in _dialogue)
        return iter(["你好", "呀。"])


class FakeAsr:
    async def to_playground_text(self, _pcm):
        return "音频你好"


class FakeTts:
    def to_playground_wav(self, text):
        return b"WAV:" + text.encode()


class FakeMemory:
    def __init__(self):
        self.namespace = None
        self.queries = []
        self.saved = []

    def init_memory(self, memory_namespace, llm, **kwargs):
        assert llm is not None
        self.namespace = memory_namespace

    async def query_memory(self, query):
        self.queries.append(query)
        return "用户喜欢短答案"

    async def save_memory(self, messages, session_id=None):
        self.saved.append((messages, session_id))


def make_session():
    bundle = {
        "conversation_id": "conversation-a",
        "agent_id": "agent-a",
        "agent_version": 4,
        "config": {"systemPrompt": "你是一个测试角色", "rolePrompt": "语气简洁"},
        "runtime_models": {},
    }
    return PublicConversationSession(
        claims(), bundle,
        llm_factory=lambda _model: FakeLlm(),
        asr_factory=lambda _model: FakeAsr(),
        tts_factory=lambda _model: FakeTts(),
    )


@pytest.mark.asyncio
async def test_text_turn_emits_ordered_llm_and_tts_events():
    events = await make_session().handle_text(TextTurnInput("request-a", "你好"))

    assert [event.event_type for event in events] == [
        "turn.started", "llm.delta", "llm.delta", "tts.audio", "turn.completed"
    ]
    assert [event.sequence for event in events] == [1, 2, 3, 4, 5]
    assert events[3].details["mime_type"] == "audio/wav"
    assert base64.b64decode(events[3].details["data"]).startswith(b"WAV:")


@pytest.mark.asyncio
async def test_audio_turn_emits_asr_final_before_text_pipeline():
    audio = base64.b64encode(b"pcm").decode()
    events = await make_session().handle_audio(AudioTurnInput("request-a", 0, audio, True))

    assert events[0].event_type == "turn.started"
    assert events[1].event_type == "asr.final"
    assert events[1].details["text"] == "音频你好"
    assert events[-1].event_type == "turn.completed"


@pytest.mark.asyncio
async def test_duplicate_request_is_rejected_without_second_llm_call():
    session = make_session()
    await session.handle_text(TextTurnInput("request-a", "你好"))

    events = await session.handle_text(TextTurnInput("request-a", "你好"))

    assert [event.event_type for event in events] == ["error"]
    assert events[0].details["code"] == "duplicate_request"


@pytest.mark.asyncio
async def test_text_only_output_does_not_call_tts():
    now = int(time.time())
    claims_text_only = RuntimeTokenClaims(
        "conversation-text", "user-a", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), now - 1, now + 900,
    )
    tts_calls = []
    session = PublicConversationSession(
        claims_text_only,
        {"conversation_id": "conversation-text", "agent_id": "agent-a", "agent_version": 4,
         "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(),
        tts_factory=lambda _model: tts_calls.append(True) or FakeTts(),
    )

    events = await session.handle_text(TextTurnInput("request-text", "你好"))

    assert [event.event_type for event in events] == ["turn.started", "llm.delta", "llm.delta", "turn.completed"]
    assert tts_calls == []


@pytest.mark.asyncio
async def test_expired_session_and_cancel_are_terminal_and_idempotent():
    now = int(time.time())
    expired = PublicConversationSession(
        RuntimeTokenClaims("conversation-expired", "user-a", "agent-a", 4,
                           ("conversation:text",), ("text",), ("text",), now - 10, now - 1),
        {"conversation_id": "conversation-expired", "agent_id": "agent-a", "agent_version": 4,
         "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(), tts_factory=lambda _model: FakeTts(),
    )
    assert (await expired.handle_text(TextTurnInput("request-expired", "你好")))[0].event_type == "session.expired"

    session = make_session()
    turn_id, _ = session._start("cancel-request", "text")
    cancelled = await session.cancel(turn_id)
    repeated = await session.cancel(turn_id)
    assert [event.event_type for event in cancelled] == ["turn.cancelled"]
    assert repeated[0].details["code"] == "duplicate_request"
    unknown = await session.cancel("missing-turn")
    assert unknown[0].details["code"] == "unknown_turn"


@pytest.mark.asyncio
async def test_memory_uses_conversation_namespace_and_is_injected_without_leaking_events():
    memory = FakeMemory()
    captured_dialogue = []

    class MemoryLlm(FakeLlm):
        def response(self, _session_id, dialogue):
            captured_dialogue.extend(dialogue)
            return iter(["记住了。"])

    now = int(time.time())
    claims_memory = RuntimeTokenClaims(
        "conversation-memory", "user-a", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), now - 1, now + 900,
    )
    session = PublicConversationSession(
        claims_memory,
        {"conversation_id": "conversation-memory", "agent_id": "agent-a", "agent_version": 4,
         "config": {"systemPrompt": "系统", "memoryNamespace": "public:user-a:agent-a:conversation-memory"},
         "runtime_models": {"Memory": {"type": "fake-memory"}}},
        llm_factory=lambda _model: MemoryLlm(),
        tts_factory=lambda _model: FakeTts(),
    )
    session._memory_factory = lambda _model: memory

    events = await session.handle_text(TextTurnInput("request-memory", "我喜欢短答案"))

    assert memory.namespace == "public:user-a:agent-a:conversation-memory"
    assert memory.queries == ["我喜欢短答案"]
    assert memory.saved[0][1] == "conversation-memory"
    assert "用户喜欢短答案" in captured_dialogue[0]["content"]
    assert all("用户喜欢短答案" not in str(event.details) for event in events)


@pytest.mark.asyncio
async def test_memory_provider_failure_does_not_remove_primary_reply():
    class FailingMemory(FakeMemory):
        async def query_memory(self, _query):
            raise RuntimeError("provider unavailable")

        async def save_memory(self, _messages, session_id=None):
            raise RuntimeError("provider unavailable")

    now = int(time.time())
    claims_memory = RuntimeTokenClaims(
        "conversation-memory-failure", "user-a", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), now - 1, now + 900,
    )
    session = PublicConversationSession(
        claims_memory,
        {"conversation_id": "conversation-memory-failure", "agent_id": "agent-a", "agent_version": 4,
         "config": {"memoryNamespace": "public:failure"}, "runtime_models": {"Memory": {"type": "fake-memory"}}},
        llm_factory=lambda _model: FakeLlm(), tts_factory=lambda _model: FakeTts(),
    )
    session._memory_factory = lambda _model: FailingMemory()

    events = await session.handle_text(TextTurnInput("request-memory-failure", "你好"))

    assert events[-1].event_type == "turn.completed"


@pytest.mark.asyncio
async def test_published_skill_prompt_is_selected_without_exposing_tools():
    captured_dialogue = []

    class SkillLlm(FakeLlm):
        def response(self, _session_id, dialogue):
            captured_dialogue.extend(dialogue)
            return iter(["按技能回复。"])

    now = int(time.time())
    claims_skill = RuntimeTokenClaims(
        "conversation-skill", "user-a", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), now - 1, now + 900,
    )
    session = PublicConversationSession(
        claims_skill,
        {"conversation_id": "conversation-skill", "agent_id": "agent-a", "agent_version": 4,
         "config": {"skills": [{
             "id": "skill-weather", "version": 2, "packageVersion": 2,
             "packageSha256": "a" * 64, "name": "天气", "description": "天气",
             "executionPrompt": "先确认城市，再回答天气。", "semanticThreshold": 0.7,
             "responseMode": "LLM", "timeoutMs": 30000, "failureMessage": None,
             "bindingPriority": 10, "triggers": [{"type": "KEYWORD", "value": "天气", "priority": 0,
                                                     "caseSensitive": False, "enabled": True}],
             "toolNames": [], "defaults": {},
         }]}, "runtime_models": {}},
        llm_factory=lambda _model: SkillLlm(),
    )

    events = await session.handle_text(TextTurnInput("request-skill", "今天的天气怎么样"))

    assert events[-1].event_type == "turn.completed"
    assert "先确认城市，再回答天气。" in captured_dialogue[0]["content"]
    assert "tool" not in captured_dialogue[0]["content"].lower()
