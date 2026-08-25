import asyncio

import pytest

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
