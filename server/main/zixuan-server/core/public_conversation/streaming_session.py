from __future__ import annotations

import base64
import inspect
import uuid
from typing import Any

from .protocol import (
    AudioTurnInput,
    ConversationEvent,
    MAX_STREAM_FRAME_BYTES,
    MAX_STREAM_SEGMENT_BYTES,
    MAX_STREAM_SEGMENTS,
)
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
        self.segment_id: str | None = None
        self.event_id: str | None = None
        self.segment_count = 0
        self.last_final_text: str | None = None
        self.last_committed_request_id: str | None = None

    async def start(self, request_id: str, sample_rate: int, channels: int, format: str,
                    event_id: str | None = None) -> ConversationEvent:
        if self.started and not self.stopped:
            raise ValueError("stream already started")
        if sample_rate != 16000 or channels != 1 or format != "pcm_s16le":
            raise ValueError("unsupported stream audio format")
        self.started = True
        self.stopped = False
        self.request_id = request_id
        self.audio.clear()
        self.segment_id = None
        self.event_id = event_id
        self.segment_count = 0
        self.last_final_text = None
        self.last_committed_request_id = None
        await self._start_asr()
        return self._event("stream.ready", self.session._next_error_sequence(), None, {
            "sample_rate": sample_rate, "channels": channels, "format": format,
        }, request_id=request_id, event_id=event_id)

    def _event(self, event_type: str, sequence: int, turn_id: str | None,
               details: dict[str, Any], **kwargs):
        try:
            return self.session._event(event_type, sequence, turn_id, details, **kwargs)
        except TypeError:
            return self.session._event(event_type, sequence, turn_id, details)

    async def _start_asr(self) -> None:
        model = self.session.runtime_models.get("ASR") or {}
        if str(model.get("type", "")).lower() != "doubao_stream":
            self.asr = None
            return
        provider = DoubaoStreamingProvider(model)
        self.asr = PublicStreamingAsr(
            provider,
            on_partial=lambda text: self._emit_asr("asr.partial", text),
            on_final=self._on_final,
        )
        try:
            await self.asr.start()
        except Exception:
            self.asr = None

    async def _emit_asr(self, event_type: str, text: str) -> None:
        event = self._event(
            event_type, self.session._next_error_sequence(), None,
            {"text": text}, request_id=self.request_id, segment_id=self.segment_id,
            event_id=self.event_id,
        )
        result = self.emit(event)
        if inspect.isawaitable(result):
            await result

    async def push_audio(self, data: bytes) -> None:
        if not self.started or self.stopped:
            raise ValueError("stream is not active")
        if not isinstance(data, (bytes, bytearray)) or not data:
            raise ValueError("audio frame is invalid")
        if len(data) > MAX_STREAM_FRAME_BYTES:
            raise ValueError("audio frame is too large")
        if len(self.audio) + len(data) > MAX_STREAM_SEGMENT_BYTES:
            raise ValueError("audio segment is too long")
        if not self.audio and self.segment_id is None:
            self.segment_id = uuid.uuid4().hex
            self.last_final_text = None
            self.event_id = None
        if self.asr is None and not self.audio:
            await self._start_asr()
        self.audio.extend(data)
        if self.asr is not None:
            try:
                await self.asr.push(data)
            except Exception:
                self.asr = None

    async def end_audio(self, request_id: str | None = None, duration_ms: int | None = None,
                        event_id: str | None = None) -> AudioTurnInput | None:
        if not self.started or self.stopped:
            raise ValueError("stream is not active")
        requested_request_id = request_id
        request_id = request_id or self.request_id
        if not request_id:
            raise ValueError("stream request_id is missing")
        if not self.audio and (requested_request_id is None or request_id == self.last_committed_request_id):
            return None
        if not self.audio:
            raise ValueError("audio segment is empty")
        if self.segment_count >= MAX_STREAM_SEGMENTS:
            raise ValueError("stream segment limit exceeded")
        handled_by_final = False
        self.request_id = request_id
        self.segment_id = self.segment_id or uuid.uuid4().hex
        self.event_id = event_id
        encoded = base64.b64encode(bytes(self.audio)).decode("ascii")
        measured_duration_ms = max(1, len(self.audio) * 1000 // (16000 * 2))
        item = AudioTurnInput(request_id, 0, encoded, True, measured_duration_ms, self.segment_id, event_id)
        if self.asr is not None:
            # The ASR final belongs to this VAD segment, not to stream.start.
            active_asr = self.asr
            try:
                handled_by_final = bool(await active_asr.end())
            except Exception:
                pass
            finally:
                self.asr = None
                try:
                    await active_asr.cancel()
                except Exception:
                    pass
        self.segment_count += 1
        self.last_committed_request_id = request_id
        self.audio.clear()
        self.request_id = None
        self.duration_ms = None
        self.segment_id = None
        return None if handled_by_final else item

    async def end_utterance(self, request_id: str | None = None) -> AudioTurnInput | None:
        return await self.end_audio(request_id)

    async def _on_final(self, text: str) -> bool:
        request_id = self.request_id
        segment_id = self.segment_id
        event_id = self.event_id
        self.last_final_text = str(text or "").strip() or None
        self.request_id = None
        result = None
        if self.on_final is not None:
            try:
                signature = inspect.signature(self.on_final)
                accepts_segment = "segment_id" in signature.parameters or any(
                    parameter.kind == inspect.Parameter.VAR_KEYWORD
                    for parameter in signature.parameters.values()
                )
            except (TypeError, ValueError):
                accepts_segment = False
            try:
                signature = inspect.signature(self.on_final)
                accepts_event = "event_id" in signature.parameters or any(
                    parameter.kind == inspect.Parameter.VAR_KEYWORD
                    for parameter in signature.parameters.values()
                )
            except (TypeError, ValueError):
                accepts_event = False
            if accepts_segment or accepts_event:
                kwargs = {}
                if accepts_segment:
                    kwargs["segment_id"] = segment_id
                if accepts_event:
                    kwargs["event_id"] = event_id
                result = self.on_final(request_id, text, **kwargs)
            else:
                result = self.on_final(request_id, text)
            if inspect.isawaitable(result):
                result = await result
        return bool(result)

    def stop(self) -> None:
        import asyncio
        asyncio.create_task(self.aclose())

    async def aclose(self) -> None:
        if self.stopped:
            return
        self.stopped = True
        self.started = False
        self.audio.clear()
        self.request_id = None
        self.segment_id = None
        self.event_id = None
        self.last_final_text = None
        if self.asr is not None:
            asr, self.asr = self.asr, None
            await asr.cancel()
