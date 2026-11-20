import asyncio
import time

import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession


class FakeLlm:
    def response(self, _session_id, _dialogue):
        return iter(["远程历史回复"])


@pytest.mark.asyncio
async def test_history_writer_and_loader_are_optional_cross_process_hooks():
    written = []

    async def writer(item):
        written.append(item)

    async def loader(limit):
        assert limit == 10
        return [{"turn_id": "remote-turn", "text": "之前", "reply": "远程", "occurred_at": 1}]

    now = int(time.time())
    session = PublicConversationSession(
        RuntimeTokenClaims("conversation-history", "user-a", "agent-a", 1,
                           ("conversation:text",), ("text",), ("text",), now - 1, now + 900),
        {"conversation_id": "conversation-history", "agent_id": "agent-a", "agent_version": 1,
         "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(), history_loader=loader, history_writer=writer,
    )

    await session.handle_text(TextTurnInput("request-a", "当前"))
    history = await session.history_async(10)

    assert written[0]["text"] == "当前"
    assert history.details["items"][0]["turn_id"] == "remote-turn"


class SlowTts:
    """读得很慢的语音合成：用来模拟回复的文字已经显示完、语音还没读完的那段时间。"""
    audio_file_type = "mp3"

    def __init__(self):
        self.started = asyncio.Event()

    async def text_to_speak(self, _text, _output_file):
        self.started.set()
        await asyncio.sleep(30)
        return b"audio"


def speaking_session(conversation_id, tts, writer):
    now = int(time.time())
    return PublicConversationSession(
        RuntimeTokenClaims(conversation_id, "user-a", "agent-a", 1,
                           ("conversation:text",), ("text",), ("text", "audio"), now - 1, now + 900),
        {"conversation_id": conversation_id, "agent_id": "agent-a", "agent_version": 1,
         "config": {}, "runtime_models": {"TTS": {}}},
        llm_factory=lambda _model: FakeLlm(), tts_factory=lambda _model: tts, history_writer=writer,
    )


@pytest.mark.asyncio
async def test_turn_is_recorded_when_the_user_leaves_before_the_reply_is_read_out():
    written = []

    async def writer(item):
        written.append(item)

    tts = SlowTts()
    session = speaking_session("conversation-leave", tts, writer)
    turn_id, events = session.begin_text(TextTurnInput("request-a", "讲个冷知识"))
    task = asyncio.create_task(session.finish_text(turn_id, "讲个冷知识", events))
    await asyncio.wait_for(tts.started.wait(), timeout=5)

    # 用户关掉了页面：连接断开时，处理程序会取消还在进行的这一轮。
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert [(item["text"], item["reply"]) for item in written] == [("讲个冷知识", "远程历史回复")]
    assert "usage" in written[0]
    assert not any(event.event_type == "turn.completed" for event in events)


@pytest.mark.asyncio
async def test_turn_is_recorded_when_the_user_interrupts_the_reply_while_it_is_read_out():
    written = []

    async def writer(item):
        written.append(item)

    tts = SlowTts()
    session = speaking_session("conversation-interrupt", tts, writer)
    turn_id, events = session.begin_text(TextTurnInput("request-a", "讲个冷知识"))
    task = asyncio.create_task(session.finish_text(turn_id, "讲个冷知识", events))
    session.attach_task(turn_id, task)
    await asyncio.wait_for(tts.started.wait(), timeout=5)

    cancelled = await session.cancel(turn_id)
    await asyncio.gather(task, return_exceptions=True)

    assert [event.event_type for event in cancelled] == ["turn.cancelled"]
    assert [(item["text"], item["reply"]) for item in written] == [("讲个冷知识", "远程历史回复")]
    # 同一个会话里接着聊时，角色也记得被打断的那一轮。
    history = await session.history_async(10)
    assert [item["reply"] for item in history.details["items"]] == ["远程历史回复"]
