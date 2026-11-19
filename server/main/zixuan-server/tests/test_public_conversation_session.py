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
        assert all(message.get("role") in {"system", "user", "assistant"} for message in _dialogue)
        assert _dialogue[-1]["role"] == "user"
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


class RecordingLlm:
    """Replies with a numbered answer and remembers every dialogue it was given."""

    tools_enabled = False

    def __init__(self):
        self.dialogues = []

    def response(self, _session_id, dialogue):
        self.dialogues.append([dict(message) for message in dialogue])
        return iter([f"回答{len(self.dialogues)}"])


@pytest.mark.asyncio
async def test_earlier_turns_of_the_session_are_given_to_the_model_in_order():
    llm = RecordingLlm()
    session = make_session()
    session._llm_factory = lambda _model: llm

    await session.handle_text(TextTurnInput("request-a", "我的猫叫年糕"))
    await session.handle_text(TextTurnInput("request-b", "我今天升职了"))
    await session.handle_text(TextTurnInput("request-c", "我的猫叫什么？"))

    first, _, third = llm.dialogues
    assert [message["role"] for message in first] == ["system", "user"]
    assert [(message["role"], message["content"]) for message in third[1:]] == [
        ("user", "我的猫叫年糕"), ("assistant", "回答1"),
        ("user", "我今天升职了"), ("assistant", "回答2"),
        ("user", "我的猫叫什么？"),
    ]


@pytest.mark.asyncio
async def test_context_is_bounded_and_keeps_the_most_recent_turns(monkeypatch):
    import core.public_conversation.session as session_module

    monkeypatch.setattr(session_module, "MAX_CONTEXT_TURNS", 2)
    llm = RecordingLlm()
    session = make_session()
    session._llm_factory = lambda _model: llm

    for index in range(4):
        await session.handle_text(TextTurnInput(f"request-{index}", f"第{index + 1}句"))

    last = llm.dialogues[-1]
    assert [message["content"] for message in last[1:]] == ["第2句", "回答2", "第3句", "回答3", "第4句"]

    monkeypatch.setattr(session_module, "MAX_CONTEXT_TURNS", 20)
    monkeypatch.setattr(session_module, "MAX_CONTEXT_CHARS", 8)
    await session.handle_text(TextTurnInput("request-x", "最后一句"))
    assert [message["content"] for message in llm.dialogues[-1][1:]] == ["第4句", "回答4", "最后一句"]


@pytest.mark.asyncio
async def test_a_failed_turn_leaves_no_half_exchange_in_the_context():
    class FailingOnce(RecordingLlm):
        def response(self, session_id, dialogue):
            if len(self.dialogues) == 1:
                self.dialogues.append([dict(message) for message in dialogue])
                raise RuntimeError("upstream unavailable")
            return super().response(session_id, dialogue)

    llm = FailingOnce()
    session = make_session()
    session._llm_factory = lambda _model: llm

    await session.handle_text(TextTurnInput("request-a", "第一句"))
    failed = await session.handle_text(TextTurnInput("request-b", "第二句"))
    await session.handle_text(TextTurnInput("request-c", "第三句"))

    assert any(event.event_type == "error" for event in failed)
    assert [message["content"] for message in llm.dialogues[-1][1:]] == ["第一句", "回答1", "第三句"]


class HeaderLlm:
    """A model that follows the role prompt and opens the reply with an emotion marker."""

    tools_enabled = False

    def __init__(self, chunks):
        self._chunks = chunks

    def response(self, _session_id, _dialogue):
        return iter(self._chunks)


@pytest.mark.asyncio
@pytest.mark.parametrize("chunks", [
    ["emotion: happy\n", "你好呀，", "欢迎你来。"],
    ["emo", "tion：happy\n你好呀，", "欢迎你来。"],
    ['{"emotion": "happy"}\n你好呀，', "欢迎你来。"],
    ["[emotion: happy]\n你好呀，", "欢迎你来。"],
    ["【emotion：gentle】你好呀，", "欢迎你来。"],
    ["【emo", "tion：gentle】", "你好呀，欢迎你来。"],
])
async def test_emotion_marker_never_reaches_the_text_or_the_voice(chunks):
    session = make_session()
    session._llm_factory = lambda _model: HeaderLlm(chunks)

    events = await session.handle_text(TextTurnInput("request-a", "你好"))

    spoken = "".join(event.details["text"] for event in events if event.event_type == "llm.delta")
    assert spoken == "你好呀，欢迎你来。"
    audio = next(event for event in events if event.event_type == "tts.audio")
    assert base64.b64decode(audio.details["data"]) == b"WAV:" + "你好呀，欢迎你来。".encode()
    assert events[-1].details["text"] == "你好呀，欢迎你来。"


@pytest.mark.asyncio
async def test_the_model_is_told_the_exact_marker_format():
    seen = []

    class Capture(HeaderLlm):
        def response(self, _session_id, dialogue):
            seen.extend(dialogue)
            return iter(self._chunks)

    session = make_session()
    session._llm_factory = lambda _model: Capture(['{"emotion":"gentle"}\n在的。'])

    await session.handle_text(TextTurnInput("request-a", "你好"))

    system = next(message["content"] for message in seen if message["role"] == "system")
    assert "<companion_reply_protocol>" in system
    assert system.startswith("你是一个测试角色")


@pytest.mark.asyncio
@pytest.mark.parametrize("chunks", [["【提醒】", "记得喝水"], ["（小声）", "晚安"], ["[1] 第一件事"]])
async def test_bracketed_text_that_is_not_a_marker_is_kept(chunks):
    session = make_session()
    session._llm_factory = lambda _model: HeaderLlm(chunks)

    events = await session.handle_text(TextTurnInput("request-a", "你好"))

    assert events[-1].details["text"] == "".join(chunks)


@pytest.mark.asyncio
async def test_plain_replies_stream_immediately_without_waiting_for_a_line_break():
    session = make_session()
    session._llm_factory = lambda _model: HeaderLlm(["今天", "过得", "怎么样"])

    events = await session.handle_text(TextTurnInput("request-a", "你好"))

    assert [event.details["text"] for event in events if event.event_type == "llm.delta"] == ["今天", "过得", "怎么样"]


class TaggedAsr:
    """Mimics the local SenseVoice provider, which returns the text with language and emotion tags."""

    async def speech_to_text_wrapper(self, _frames, _session_id, raise_errors=False):
        return {"content": " 音频你好 ", "language": "zh", "emotion": "🙂"}, None


@pytest.mark.asyncio
async def test_audio_turn_uses_only_the_text_of_a_tagged_transcript():
    session = make_session()
    session._asr_factory = lambda _model: TaggedAsr()
    seen = []
    original = session._run_text

    async def capture(turn_id, text, events, emit=None):
        seen.append(text)
        return await original(turn_id, text, events, emit)

    session._run_text = capture
    audio = base64.b64encode(b"pcm").decode()
    events = await session.handle_audio(AudioTurnInput("request-a", 0, audio, True))

    assert events[1].event_type == "asr.final"
    assert events[1].details["text"] == "音频你好"
    assert seen == ["音频你好"]
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


class MeteredLlm:
    """Reports token usage the way the OpenAI-compatible provider does."""

    tools_enabled = False

    def __init__(self):
        self.last_usage = None

    def response(self, _session_id, _dialogue):
        self.last_usage = {"prompt_tokens": 321, "completion_tokens": 12}
        return iter(["好的", "呀"])


def metered_session(writer, config=None):
    bundle = {
        "conversation_id": "conversation-a", "agent_id": "agent-a", "agent_version": 4,
        "config": {"systemPrompt": "你是一个测试角色", **(config or {})},
        "runtime_models": {"LLM": {"model_name": "deepseek-chat"}},
    }
    return PublicConversationSession(
        claims(), bundle,
        llm_factory=lambda _model: MeteredLlm(),
        asr_factory=lambda _model: FakeAsr(),
        tts_factory=lambda _model: FakeTts(),
        history_writer=writer,
    )


@pytest.mark.asyncio
async def test_each_completed_turn_reports_what_it_used():
    written = []

    async def writer(item):
        written.append(item)
        return {"remaining_turns": 5}

    session = metered_session(writer)
    await session.handle_text(TextTurnInput("request-a", "你好呀"))

    usage = written[0]["usage"]
    assert usage["input_mode"] == "text"
    assert usage["llm_model"] == "deepseek-chat"
    assert usage["input_chars"] == 3
    assert usage["reply_chars"] == 3
    assert usage["tts_chars"] == 3
    assert usage["prompt_tokens"] == 321
    assert usage["completion_tokens"] == 12
    assert usage["first_text_ms"] is not None and usage["total_ms"] >= usage["first_text_ms"]
    # 用量只随上报走，不混进会话内的对话历史。
    assert "usage" not in session.history().details["items"][0]


@pytest.mark.asyncio
async def test_no_new_turn_starts_once_todays_allowance_is_used_up():
    async def writer(_item):
        return {"remaining_turns": 0}

    session = metered_session(writer)
    first = await session.handle_text(TextTurnInput("request-a", "你好"))
    second = await session.handle_text(TextTurnInput("request-b", "还在吗"))

    assert first[-1].event_type == "turn.completed"
    assert [event.event_type for event in second] == ["error"]
    assert second[0].details["code"] == "daily_quota_exceeded"
    assert second[0].details["retryable"] is False


@pytest.mark.asyncio
async def test_a_session_only_gets_as_many_turns_as_the_user_has_left_today():
    async def writer(_item):
        return None

    session = metered_session(writer, {"turnBudget": 1})
    await session.handle_text(TextTurnInput("request-a", "你好"))
    blocked = await session.handle_audio(AudioTurnInput("request-b", 0, base64.b64encode(b"pcm").decode(), True))

    assert blocked[0].details["code"] == "daily_quota_exceeded"

    unlimited = metered_session(writer, {"turnBudget": "oops"})
    events = await unlimited.handle_text(TextTurnInput("request-a", "你好"))
    assert events[-1].event_type == "turn.completed"
