import asyncio
import base64
import time

import pytest

from core.public_conversation.protocol import (
    ConversationEvent,
    RuntimeTokenClaims,
    StreamControlFrame,
    StreamStartInput,
    TextTurnInput,
)
from core.public_conversation.session import PublicConversationSession
from core.public_conversation.streaming_session import PublicStreamingSession


def test_web_session_start_negotiates_protocol_version_and_audio_commit_alias():
    start = StreamStartInput.from_payload({
        "type": "web.session.start",
        "protocol_version": 1,
        "request_id": "stream-1",
        "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1},
    })
    assert start.protocol_version == 1
    assert StreamControlFrame.from_payload({
        "type": "input.audio.commit", "request_id": "segment-1"
    }).type == "input.audio.commit"
    assert StreamControlFrame.from_payload({
        "type": "response.cancel", "turn_id": "turn-1", "event_id": "event-1"
    }).type == "turn.cancel"


def test_event_carries_request_and_segment_associations():
    payload = ConversationEvent(
        "asr.partial", "conversation-a", "turn-a", 1, 1710000000000,
        {"text": "你好"}, request_id="request-a", segment_id="segment-a"
    ).to_dict()
    assert payload["request_id"] == "request-a"
    assert payload["segment_id"] == "segment-a"


def test_event_can_echo_a_client_event_id():
    payload = ConversationEvent(
        "session.ready", "conversation-a", None, 1, 1710000000000,
        {}, event_id="evt-a"
    ).to_dict()
    assert payload["event_id"] == "evt-a"


class _Provider:
    instances = []

    def __init__(self, _config):
        self.frames = []
        self.cancelled = False
        self.__class__.instances.append(self)

    async def start(self, on_partial, on_final):
        self.on_final = on_final

    async def push(self, frame):
        self.frames.append(frame)

    async def end(self):
        await self.on_final("segment transcript")

    async def cancel(self):
        self.cancelled = True


@pytest.mark.asyncio
async def test_stream_recreates_asr_provider_for_each_audio_segment(monkeypatch):
    import core.public_conversation.streaming_session as module

    monkeypatch.setattr(module, "DoubaoStreamingProvider", _Provider)

    class Session:
        runtime_models = {"ASR": {"type": "doubao_stream"}}

        def _event(self, event_type, sequence, turn_id, details, **kwargs):
            return ConversationEvent(event_type, "conversation-a", turn_id, sequence,
                                     int(time.time() * 1000), details, **kwargs)

        def _next_error_sequence(self):
            return 1

    finals = []
    stream = PublicStreamingSession(Session(), lambda _event: None,
                                    lambda request_id, text, **kwargs: finals.append((request_id, text, kwargs)))
    await stream.start("stream-1", 16000, 1, "pcm_s16le")
    await stream.push_audio(b"a" * 320)
    first = await stream.end_audio("request-1")
    await stream.push_audio(b"b" * 320)
    second = await stream.end_audio("request-2")

    assert first.request_id == "request-1"
    assert second.request_id == "request-2"
    assert len(_Provider.instances) == 2
    assert finals[0][0] == "request-1"
    assert finals[1][0] == "request-2"
    assert finals[0][2]["segment_id"] != finals[1][2]["segment_id"]


@pytest.mark.asyncio
async def test_streaming_asr_final_handoff_consumes_segment_without_duplicate_audio_turn(monkeypatch):
    import core.public_conversation.streaming_session as module

    class Provider(_Provider):
        async def end(self):
            await self.on_final("你好")

    monkeypatch.setattr(module, "DoubaoStreamingProvider", Provider)

    class Session:
        runtime_models = {"ASR": {"type": "doubao_stream"}}

        def _event(self, event_type, sequence, turn_id, details, **kwargs):
            return ConversationEvent(event_type, "conversation-a", turn_id, sequence,
                                     int(time.time() * 1000), details, **kwargs)

        def _next_error_sequence(self):
            return 1

    callbacks = []

    async def handoff(request_id, text, *, segment_id=None, event_id=None):
        callbacks.append((request_id, text, segment_id, event_id))
        return True

    stream = PublicStreamingSession(Session(), lambda _event: None, handoff)
    await stream.start("stream-1", 16000, 1, "pcm_s16le")
    await stream.push_audio(b"pcm")
    item = await stream.end_audio("request-1", 100, "event-1")

    assert item is None
    assert callbacks[0][0] == "request-1"
    assert callbacks[0][1] == "你好"
    assert callbacks[0][3] == "event-1"
@pytest.mark.asyncio
async def test_stream_rejects_oversized_frame_and_segment_duration():
    class Session:
        runtime_models = {}

        def _event(self, event_type, sequence, turn_id, details, **kwargs):
            return ConversationEvent(event_type, "conversation-a", turn_id, sequence,
                                     int(time.time() * 1000), details, **kwargs)

        def _next_error_sequence(self):
            return 1

    stream = PublicStreamingSession(Session(), lambda _event: None)
    await stream.start("stream-1", 16000, 1, "pcm_s16le")
    with pytest.raises(ValueError, match="frame"):
        await stream.push_audio(b"x" * (256 * 1024 + 1))
    for _ in range(7):
        await stream.push_audio(b"x" * (256 * 1024))
    with pytest.raises(ValueError, match="segment"):
        await stream.push_audio(b"x" * (256 * 1024))


@pytest.mark.asyncio
async def test_cancel_stops_task_without_late_events():
    now = int(time.time())
    claims = RuntimeTokenClaims("conversation-a", "user-a", "agent-a", 1,
                                ("conversation:text",), ("text",), ("text",),
                                now - 1, now + 900)

    class Llm:
        def response(self, _id, _dialogue):
            async def values():
                yield "late"
            return iter(["first", "second"])

    session = PublicConversationSession(claims, {"runtime_models": {}, "config": {}},
                                        llm_factory=lambda _model: Llm())
    turn_id, events = session.begin_text(TextTurnInput("r1", "hi"))
    task = asyncio.create_task(session.finish_text(turn_id, "hi", events))
    session.attach_task(turn_id, task)
    await session.cancel(turn_id)
    await asyncio.sleep(0)
    assert task.cancelled() or task.done()
    assert not session._active_turns
