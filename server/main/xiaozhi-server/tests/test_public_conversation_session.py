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
        return iter(["你好", "呀。"])


class FakeAsr:
    async def to_playground_text(self, _pcm):
        return "音频你好"


class FakeTts:
    def to_playground_wav(self, text):
        return b"WAV:" + text.encode()


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
