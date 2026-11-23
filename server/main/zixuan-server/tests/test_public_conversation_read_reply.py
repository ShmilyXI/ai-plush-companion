import base64
import time

import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession


class Llm:
    def response(self, _session_id, _dialogue, **_kwargs):
        return iter(["好呀。今天也辛苦啦。"])


class Tts:
    def __init__(self, fail=False):
        self.fail = fail
        self.spoken = []

    def to_playground_wav(self, text):
        if self.fail:
            raise RuntimeError("tts down")
        self.spoken.append(text)
        return f"wav:{text}".encode()


def session_with(tts, output_modes=("text", "audio")):
    now = int(time.time())
    session = PublicConversationSession(
        RuntimeTokenClaims("conversation-read", "user-a", "agent-a", 1,
                           ("conversation:text",), ("text",), output_modes, now - 1, now + 900),
        {"conversation_id": "conversation-read", "agent_id": "agent-a", "agent_version": 1,
         "config": {"historyThread": True, "profileName": "小满"}, "runtime_models": {"TTS": {}}},
        llm_factory=lambda _model: Llm(), tts_factory=lambda _model: tts,
    )
    return session


@pytest.mark.asyncio
async def test_reads_back_a_reply_from_the_earlier_conversation():
    tts = Tts()
    session = session_with(tts)
    session.seed_history([{"turn_id": "old-1", "text": "我回来了", "reply": "欢迎回来。今天过得怎么样？", "occurred_at": 1}])

    events = await session.read_reply("read-1", "old-1", [])

    audio = [event for event in events if event.event_type == "tts.audio"]
    # 按句合成（太短的句子会和下一句并在一起），合起来就是那条回复。
    assert audio and "".join(event.details["text"] for event in audio) == "欢迎回来。今天过得怎么样？"
    assert base64.b64decode(audio[0].details["data"]).decode().startswith("wav:欢迎回来。")
    assert all(event.turn_id == "old-1" and event.details["purpose"] == "read" for event in audio)
    assert events[-1].event_type == "reply.read.done"


@pytest.mark.asyncio
async def test_reads_a_reply_said_in_this_session_without_using_up_a_turn():
    tts = Tts()
    session = session_with(tts)
    events = await session.handle_text(TextTurnInput("request-a", "我下班了"))
    turn_id = next(event.turn_id for event in events if event.event_type == "turn.completed")
    used = len(session._completed_turns)

    read = await session.read_reply("read-2", turn_id, [])

    assert read[-1].event_type == "reply.read.done"
    assert len(session._completed_turns) == used


@pytest.mark.asyncio
async def test_will_not_read_text_the_role_never_said_and_reports_a_failure_instead():
    session = session_with(Tts())
    missing = await session.read_reply("read-3", "made-up-turn", [])
    assert [event.event_type for event in missing] == ["reply.read.failed"]
    assert missing[0].details["reason"] == "not_found"

    broken = session_with(Tts(fail=True))
    broken.seed_history([{"turn_id": "old-1", "text": "嗨", "reply": "嗨呀。", "occurred_at": 1}])
    failed = await broken.read_reply("read-4", "old-1", [])
    assert [event.event_type for event in failed] == ["reply.read.failed"]
    assert failed[0].details["reason"] == "tts_failed"

    silent = session_with(Tts(), output_modes=("text",))
    silent.seed_history([{"turn_id": "old-1", "text": "嗨", "reply": "嗨呀。", "occurred_at": 1}])
    assert (await silent.read_reply("read-5", "old-1", []))[0].details["reason"] == "no_audio"
