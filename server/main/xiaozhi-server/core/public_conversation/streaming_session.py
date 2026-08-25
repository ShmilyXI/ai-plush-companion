from __future__ import annotations

import base64
import uuid
from typing import Any

from .protocol import AudioTurnInput, ConversationEvent
from .streaming_asr import DoubaoStreamingProvider, PublicStreamingAsr


class PublicStreamingSession:
    """Compatibility streaming facade; provider streaming is added behind this boundary."""

    def __init__(self, session: Any, emit, on_final=None):
        self.session = session
        self.emit = emit
        self.on_final = on_final
        self.started = False
        self.stopped = False
        self.audio = bytearray()
        self.request_id: str | None = None
        self.duration_ms: int | None = None
        self.asr = None

    async def start(self, request_id: str, sample_rate: int, channels: int, format: str) -> ConversationEvent:
        if self.started and not self.stopped:
            raise ValueError("stream already started")
        if sample_rate != 16000 or channels != 1 or format != "pcm_s16le":
            raise ValueError("unsupported stream audio format")
        self.started = True
        self.stopped = False
        self.request_id = request_id
        self.audio.clear()
        model = self.session.runtime_models.get("ASR") or {}
        if str(model.get("type", "")).lower() == "doubao_stream":
            provider = DoubaoStreamingProvider(model)
            self.asr = PublicStreamingAsr(
                provider,
                on_partial=lambda text: self.emit(self.session._event("asr.partial", self.session._next_error_sequence(), None, {"text": text})),
                on_final=self._on_final,
            )
            try:
                await self.asr.start()
            except Exception:
                self.asr = None
        return self.session._event("stream.ready", self.session._next_error_sequence(), None, {
            "sample_rate": sample_rate, "channels": channels, "format": format,
        })

    async def push_audio(self, data: bytes) -> None:
        if not self.started or self.stopped:
            raise ValueError("stream is not active")
        self.audio.extend(data)
        if self.asr is not None:
            try:
                await self.asr.push(data)
            except Exception:
                self.asr = None

    async def end_audio(self, request_id: str | None = None, duration_ms: int | None = None) -> AudioTurnInput | None:
        if not self.started or self.stopped:
            raise ValueError("stream is not active")
        request_id = request_id or self.request_id
        if not request_id:
            raise ValueError("stream request_id is missing")
        encoded = base64.b64encode(bytes(self.audio)).decode("ascii")
        item = AudioTurnInput(request_id, 0, encoded, True, duration_ms)
        if self.asr is not None:
            try:
                await self.asr.end()
            except Exception:
                self.asr = None
            if self.asr is not None and self.request_id is None:
                return None
        self.audio.clear()
        self.request_id = None
        self.duration_ms = None
        return item

    async def _on_final(self, text: str) -> None:
        request_id = self.request_id
        self.request_id = None
        await self.emit(self.session._event("asr.final", self.session._next_error_sequence(), None, {"text": text}))
        if self.on_final is not None:
            result = self.on_final(request_id, text)
            if hasattr(result, "__await__"):
                await result

    def stop(self) -> None:
        self.stopped = True
        self.started = False
        self.audio.clear()
        self.request_id = None
        if self.asr is not None:
            import asyncio
            asyncio.create_task(self.asr.cancel())
            self.asr = None
