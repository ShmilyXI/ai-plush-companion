import base64
import time

import pytest

from core.public_conversation.protocol import AudioTurnInput, MAX_TEXT_LENGTH, RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession
from core.utils.dialogue import Message


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
        assert all(message.get("role") in {"system", "user", "assistant", "tool"} for message in _dialogue)
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
async def test_continuation_rebuilds_persisted_dialogue_before_current_turn():
    captured = []

    class HistoryLlm(FakeLlm):
        def response(self, _session_id, dialogue):
            captured.extend(dialogue)
            return iter(["接着聊。"])

    session = PublicConversationSession(
        claims(),
        {
            "conversation_id": "conversation-a",
            "agent_id": "agent-a",
            "agent_version": 4,
            "config": {
                "systemPrompt": "系统",
                "history": [
                    {"role": "user", "content": "我上次说喜欢散步", "reply": "记得，周末去走走。"},
                    {"user_text": "第二个问题", "assistant_text": "第二个回答"},
                ],
            },
            "runtime_models": {},
        },
        llm_factory=lambda _model: HistoryLlm(),
    )

    await session.handle_text(TextTurnInput("request-history-context", "现在继续"))

    assert [message["role"] for message in captured] == [
        "system", "user", "assistant", "user", "assistant", "user",
    ]
    assert captured[-1]["content"] == "现在继续"
    assert len(captured[1]["content"]) <= MAX_TEXT_LENGTH


@pytest.mark.asyncio
async def test_audio_turn_emits_asr_final_before_text_pipeline():
    audio = base64.b64encode(b"pcm").decode()
    events = await make_session().handle_audio(AudioTurnInput("request-a", 0, audio, True))

    assert events[0].event_type == "turn.started"
    assert events[1].event_type == "asr.final"
    assert events[1].details["text"] == "音频你好"
    assert events[-1].event_type == "turn.completed"


def test_stream_transcript_starts_an_audio_turn_without_text_scope():
    now = int(time.time())
    audio_only = PublicConversationSession(
        RuntimeTokenClaims("conversation-audio", "user-a", "agent-a", 4,
                           ("conversation:audio",), ("audio",), ("text",), now - 1, now + 900),
        {"conversation_id": "conversation-audio", "agent_id": "agent-a", "agent_version": 4,
         "config": {}, "runtime_models": {}},
    )

    turn_id, events = audio_only.begin_audio_transcript("request-a", "你好", "segment-a")

    assert turn_id
    assert events[0].event_type == "turn.started"
    assert events[0].details["input_mode"] == "audio"
    assert events[0].segment_id == "segment-a"


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
async def test_audio_only_output_does_not_emit_text_payloads():
    now = int(time.time())
    audio_only_claims = RuntimeTokenClaims(
        "conversation-audio-only", "user-a", "agent-a", 4,
        ("conversation:audio",), ("text",), ("audio",), now - 1, now + 900,
    )
    session = PublicConversationSession(
        audio_only_claims,
        {"conversation_id": "conversation-audio-only", "agent_id": "agent-a",
         "agent_version": 4, "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(),
        tts_factory=lambda _model: FakeTts(),
    )

    events = await session.handle_text(TextTurnInput("request-audio-only", "你好"))

    assert all(event.event_type != "llm.delta" for event in events)
    completed = next(event for event in events if event.event_type == "turn.completed")
    assert "text" not in completed.details
    audio = next(event for event in events if event.event_type == "tts.audio")
    assert "text" not in audio.details


@pytest.mark.asyncio
async def test_public_tts_preserves_the_provider_mime_for_complete_mp3_output():
    now = int(time.time())
    claims_mp3 = RuntimeTokenClaims(
        "conversation-mp3", "user-a", "agent-a", 4,
        ("conversation:text",), ("text",), ("text", "audio"), now - 1, now + 900,
    )

    class Mp3Tts:
        audio_file_type = "mp3"

        def text_to_speak(self, _text, _output_file):
            return b"ID3" + b"fake-mp3"

    session = PublicConversationSession(
        claims_mp3,
        {"conversation_id": "conversation-mp3", "agent_id": "agent-a",
         "agent_version": 4, "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(),
        tts_factory=lambda _model: Mp3Tts(),
    )

    events = await session.handle_text(TextTurnInput("request-mp3", "你好"))

    audio = next(event for event in events if event.event_type == "tts.audio")
    assert audio.details["mime_type"] == "audio/mpeg"


@pytest.mark.asyncio
async def test_public_stream_tts_uses_provider_encoder_sample_rate_for_wav(monkeypatch):
    captured = {}

    def convert(frames, sample_rate=16000, channels=1):
        captured["frames"] = frames
        captured["sample_rate"] = sample_rate
        captured["channels"] = channels
        return b"RIFF0000WAVE"

    monkeypatch.setattr("core.utils.util.opus_datas_to_wav_bytes", convert)

    class StreamTts:
        class Encoder:
            sample_rate = 24000
            channels = 1

        opus_encoder = Encoder()

        def text_to_speak(self, _text, _output_file):
            return None

        def to_tts(self, _text):
            return [b"opus-frame"]

    session = PublicConversationSession(
        claims(),
        {"conversation_id": "conversation-stream", "agent_id": "agent-a",
         "agent_version": 4, "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(),
        tts_factory=lambda _model: StreamTts(),
    )
    session._tts = StreamTts()

    audio, mime = await session._synthesize_public_audio("你好", {})

    assert audio.startswith(b"RIFF")
    assert mime == "audio/wav"
    assert captured == {
        "frames": [b"opus-frame"],
        "sample_rate": 24000,
        "channels": 1,
    }


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
async def test_profile_memory_initialization_passes_owner_and_profile_metadata():
    memory = FakeMemory()
    claims_memory = RuntimeTokenClaims(
        "conversation-profile", "7", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), int(time.time()) - 1, int(time.time()) + 900,
    )
    session = PublicConversationSession(
        claims_memory,
        {
            "conversation_id": "conversation-profile", "agent_id": "agent-a", "agent_version": 4,
            "config": {"profileMemoryNamespace": "companion:7:agent-a", "memoryEnabled": True},
            "runtime_models": {"Memory": {"type": "fake-memory"}},
        },
        llm_factory=lambda _model: FakeLlm(),
    )
    captured = {}

    def make_memory(_model):
        class CapturingMemory(FakeMemory):
            def init_memory(self, memory_namespace, llm, **kwargs):
                captured["namespace"] = memory_namespace
                captured["metadata"] = kwargs["source_metadata"]
                captured["save_to_file"] = kwargs["save_to_file"]

        return CapturingMemory()

    session._memory_factory = make_memory
    session._llm = FakeLlm()

    await session._query_memory("你好")

    assert captured["namespace"] == "companion:7:agent-a"
    assert captured["metadata"]["source_user_id"] == "7"
    assert captured["metadata"]["source_profile_id"] == "agent-a"
    assert captured["metadata"]["source_conversation_id"] == "conversation-profile"
    assert captured["save_to_file"] is True


@pytest.mark.asyncio
async def test_profile_memory_initialization_rejects_foreign_namespace():
    claims_memory = RuntimeTokenClaims(
        "conversation-profile", "7", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), int(time.time()) - 1, int(time.time()) + 900,
    )
    created = []
    session = PublicConversationSession(
        claims_memory,
        {
            "config": {"profileMemoryNamespace": "companion:8:agent-a"},
            "runtime_models": {"Memory": {"type": "fake-memory"}},
        },
    )
    session._memory_factory = lambda _model: created.append(True) or FakeMemory()
    session._llm = FakeLlm()

    assert await session._query_memory("你好") is None
    assert created == []


@pytest.mark.asyncio
async def test_profile_memory_initialization_ignores_legacy_summary_memory():
    captured = {}
    claims_memory = RuntimeTokenClaims(
        "conversation-profile", "7", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), int(time.time()) - 1, int(time.time()) + 900,
    )
    session = PublicConversationSession(
        claims_memory,
        {
            "config": {
                "profileMemoryNamespace": "companion:7:agent-a",
                "summaryMemory": "旧的共享摘要",
            },
            "runtime_models": {"Memory": {"type": "fake-memory"}},
        },
    )

    class CapturingMemory(FakeMemory):
        def init_memory(self, memory_namespace, llm, **kwargs):
            captured["summary_memory"] = kwargs.get("summary_memory")

    session._memory_factory = lambda _model: CapturingMemory()
    session._llm = FakeLlm()

    await session._query_memory("你好")

    assert captured["summary_memory"] is None


@pytest.mark.asyncio
async def test_legacy_memory_namespace_is_checked_when_it_uses_profile_shape():
    claims_memory = RuntimeTokenClaims(
        "conversation-profile", "7", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), int(time.time()) - 1, int(time.time()) + 900,
    )
    created = []
    session = PublicConversationSession(
        claims_memory,
        {
            "config": {"memoryNamespace": "companion:8:agent-a"},
            "runtime_models": {"Memory": {"type": "fake-memory"}},
        },
    )
    session._memory_factory = lambda _model: created.append(True) or FakeMemory()
    session._llm = FakeLlm()

    assert await session._query_memory("你好") is None
    assert created == []


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
async def test_memory_save_uses_message_objects_for_legacy_providers():
    class AttributeMemory(FakeMemory):
        async def save_memory(self, messages, session_id=None):
            assert all(isinstance(message, Message) for message in messages)
            assert [(message.role, message.content) for message in messages] == [
                ("user", "你好"),
                ("assistant", "回复"),
            ]
            self.saved.append((messages, session_id))

    session = make_session()
    session._memory = AttributeMemory()

    await session._save_memory("你好", "回复")

    assert len(session._memory.saved) == 1


@pytest.mark.asyncio
async def test_persisted_app_history_keeps_request_id_and_source():
    session = make_session()
    persisted = []
    session._history_writer = lambda item: persisted.append(item)

    turn_id, events = session.begin_text(TextTurnInput("request-history", "你好"))
    await session.finish_text(turn_id, "你好", events)

    assert persisted[0]["request_id"] == "request-history"
    assert persisted[0]["source"] == "app"


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


@pytest.mark.asyncio
async def test_completed_turns_are_available_through_bounded_history():
    session = make_session()
    await session.handle_text(TextTurnInput("request-history", "你好"))

    history = session.history(20).to_dict()

    assert history["type"] == "conversation.history"
    assert history["details"]["items"][0]["text"] == "你好"
    assert history["details"]["items"][0]["reply"] == "你好呀。"


@pytest.mark.asyncio
async def test_session_rejects_turns_after_completed_quota():
    session = make_session()
    session._completed_turns.update({f"turn-{index}" for index in range(100)})

    events = await session.handle_text(TextTurnInput("request-quota", "你好"))

    assert events[0].details["code"] == "turn_quota_exceeded"
