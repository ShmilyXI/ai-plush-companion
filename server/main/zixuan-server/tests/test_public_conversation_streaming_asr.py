import asyncio

import pytest

import core.public_conversation.streaming_session as streaming_session_module
from core.public_conversation.streaming_session import PublicStreamingSession
from core.public_conversation.streaming_asr import PublicStreamingAsr


class FakeAsr:
    def __init__(self):
        self.started = False
        self.frames = []
        self.ended = False

    async def start(self, on_partial, on_final):
        self.started = True
        self.on_partial = on_partial
        self.on_final = on_final

    async def push(self, frame):
        self.frames.append(frame)
        if len(self.frames) == 1:
            result = self.on_partial("深圳今天")
            if hasattr(result, "__await__"):
                await result

    async def end(self):
        self.ended = True
        result = self.on_final("深圳今天天气怎么样")
        if hasattr(result, "__await__"):
            await result

    async def cancel(self):
        self.ended = True


@pytest.mark.asyncio
async def test_audio_frames_produce_partial_and_final_events():
    events = []
    provider = FakeAsr()
    stream = PublicStreamingAsr(provider, on_partial=lambda text: events.append(("partial", text)), on_final=lambda text: events.append(("final", text)))
    await stream.start()
    await stream.push(b"pcm-a")
    await stream.end()
    assert events == [("partial", "深圳今天"), ("final", "深圳今天天气怎么样")]
    assert provider.frames == [b"pcm-a"]


@pytest.mark.asyncio
async def test_cancel_discards_pending_audio_and_closes_provider():
    provider = FakeAsr()
    stream = PublicStreamingAsr(provider, on_partial=lambda _text: None, on_final=lambda _text: None)
    await stream.start()
    await stream.push(b"pcm")
    await stream.cancel()
    assert stream.closed is True
    assert provider.ended is True


@pytest.mark.asyncio
async def test_stream_final_uses_audio_segment_request_id():
    class Session:
        runtime_models = {"ASR": {"type": "doubao_stream"}}

        def _next_error_sequence(self):
            return 1

        def _event(self, event_type, sequence, turn_id, details):
            return {"type": event_type, "turn_id": turn_id, "details": details}

    class Provider:
        def __init__(self, _config):
            pass

        async def start(self, on_partial, on_final):
            self.on_final = on_final

        async def push(self, _frame):
            pass

        async def end(self):
            await self.on_final("测试语音")

        async def cancel(self):
            pass

    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.setattr(streaming_session_module, "DoubaoStreamingProvider", Provider)
    try:
        callback_ids = []
        session = Session()
        stream = PublicStreamingSession(session, lambda _event: None,
                                         lambda request_id, _text: callback_ids.append(request_id))
        await stream.start("stream-request", 16000, 1, "pcm_s16le")
        await stream.push_audio(b"pcm")
        await stream.end_audio("segment-request", 100)
        assert callback_ids == ["segment-request"]
    finally:
        monkeypatch.undo()
