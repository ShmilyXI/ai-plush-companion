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
