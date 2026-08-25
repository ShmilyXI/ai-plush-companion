from __future__ import annotations

import base64
from typing import Any

from .protocol import AudioTurnInput, ConversationEvent


class PublicStreamingSession:
    """Compatibility streaming facade; provider streaming is added behind this boundary."""

    def __init__(self, session: Any, emit):
        self.session = session
        self.emit = emit
        self.started = False
        self.stopped = False
        self.audio = bytearray()
        self.request_id: str | None = None
        self.duration_ms: int | None = None

    def start(self, request_id: str, sample_rate: int, channels: int, format: str) -> ConversationEvent:
        if self.started and not self.stopped:
            raise ValueError("stream already started")
        if sample_rate != 16000 or channels != 1 or format != "pcm_s16le":
            raise ValueError("unsupported stream audio format")
        self.started = True
        self.stopped = False
        self.request_id = request_id
        self.audio.clear()
        return self.session._event("stream.ready", self.session._next_error_sequence(), None, {
            "sample_rate": sample_rate, "channels": channels, "format": format,
        })

    def push_audio(self, data: bytes) -> None:
        if not self.started or self.stopped:
            raise ValueError("stream is not active")
        self.audio.extend(data)

    def end_audio(self, request_id: str | None = None, duration_ms: int | None = None) -> AudioTurnInput:
        if not self.started or self.stopped:
            raise ValueError("stream is not active")
        request_id = request_id or self.request_id
        if not request_id:
            raise ValueError("stream request_id is missing")
        encoded = base64.b64encode(bytes(self.audio)).decode("ascii")
        item = AudioTurnInput(request_id, 0, encoded, True, duration_ms)
        self.audio.clear()
        self.request_id = None
        self.duration_ms = None
        return item

    def stop(self) -> None:
        self.stopped = True
        self.started = False
        self.audio.clear()
        self.request_id = None
