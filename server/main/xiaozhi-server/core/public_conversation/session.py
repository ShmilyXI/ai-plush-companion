from __future__ import annotations

import asyncio
import base64
import time
import uuid
from collections.abc import Callable, Mapping
from typing import Any

from .protocol import AudioTurnInput, ConversationEvent, RuntimeTokenClaims, TextTurnInput


class PublicConversationSession:
    def __init__(
        self,
        claims: RuntimeTokenClaims,
        bundle: Mapping[str, Any],
        *,
        llm_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        asr_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        tts_factory: Callable[[Mapping[str, Any]], Any] | None = None,
    ):
        self.claims = claims
        self.bundle = bundle
        self.runtime_models = bundle.get("runtime_models") or {}
        self._seen_requests: set[str] = set()
        self._cancelled_turns: set[str] = set()
        self._sequence = 0
        self._llm_factory = llm_factory or self._create_llm
        self._asr_factory = asr_factory or self._create_asr
        self._tts_factory = tts_factory or self._create_tts
        self._llm = None
        self._asr = None
        self._tts = None

    def _create_llm(self, model: Mapping[str, Any]) -> Any:
        from core.utils import llm

        return llm.create_instance(str(model["type"]), dict(model))

    def _create_asr(self, model: Mapping[str, Any]) -> Any:
        from core.utils import asr

        return asr.create_instance(str(model["type"]), dict(model), True)

    def _create_tts(self, model: Mapping[str, Any]) -> Any:
        from core.utils import tts

        return tts.create_instance(str(model["type"]), dict(model), True)

    def _event(self, event_type: str, sequence: int, turn_id: str | None, details: Mapping[str, Any] | None = None) -> ConversationEvent:
        return ConversationEvent(
            event_type=event_type,
            conversation_id=self.claims.conversation_id,
            turn_id=turn_id,
            sequence=sequence,
            occurred_at=int(time.time() * 1000),
            details=details or {},
        )

    def _next(self, events: list[ConversationEvent], event_type: str, turn_id: str | None, details: Mapping[str, Any] | None = None) -> None:
        self._sequence += 1
        events.append(self._event(event_type, self._sequence, turn_id, details))

    def _duplicate(self) -> list[ConversationEvent]:
        events: list[ConversationEvent] = []
        self._next(events, "error", None, {"code": "duplicate_request", "message": "request_id 已处理", "retryable": False})
        return events

    def ready(self) -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "session.ready", None, {"agent_version": self.claims.agent_version})
        return events[0]

    def _start(self, request_id: str, input_mode: str) -> tuple[str, list[ConversationEvent]]:
        if request_id in self._seen_requests:
            return "", self._duplicate()
        self._seen_requests.add(request_id)
        turn_id = uuid.uuid4().hex
        events: list[ConversationEvent] = []
        self._next(events, "turn.started", turn_id, {"request_id": request_id, "input_mode": input_mode})
        return turn_id, events

    def _dialogue(self, text: str) -> list[dict[str, str]]:
        config = self.bundle.get("config") or {}
        prompt = str(config.get("systemPrompt") or "")
        role_prompt = str(config.get("rolePrompt") or "")
        if role_prompt:
            prompt = f"{prompt}\n\n{role_prompt}".strip()
        messages = [{"role": "user", "content": text}]
        return ([{"role": "system", "content": prompt}] if prompt else []) + messages

    async def _run_text(self, turn_id: str, text: str, events: list[ConversationEvent]) -> list[ConversationEvent]:
        if turn_id in self._cancelled_turns:
            self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
            return events
        model = self.runtime_models.get("LLM") or {}
        if self._llm is None:
            self._llm = self._llm_factory(model)
        chunks = await asyncio.to_thread(
            lambda: list(self._llm.response(self.claims.conversation_id, self._dialogue(text)))
        )
        visible = ""
        for chunk in chunks:
            if turn_id in self._cancelled_turns:
                self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
                return events
            value = str(chunk or "")
            if value:
                visible += value
                self._next(events, "llm.delta", turn_id, {"text": value})
        if not visible:
            raise RuntimeError("LLM 未返回文本")
        model = self.runtime_models.get("TTS") or {}
        if self._tts is None:
            self._tts = self._tts_factory(model)
        if hasattr(self._tts, "to_playground_wav"):
            audio = await asyncio.to_thread(self._tts.to_playground_wav, visible)
            mime_type = "audio/wav"
        else:
            audio = await self._tts.text_to_speak(visible, None)
            mime_type = "audio/opus"
        if not isinstance(audio, bytes) or not audio:
            raise RuntimeError("TTS 未返回音频")
        self._next(events, "tts.audio", turn_id, {
            "mime_type": mime_type,
            "data": base64.b64encode(audio).decode("ascii"),
            "text": visible,
        })
        self._next(events, "turn.completed", turn_id, {"text": visible})
        return events

    async def handle_text(self, item: TextTurnInput) -> list[ConversationEvent]:
        turn_id, events = self._start(item.request_id, "text")
        if not turn_id:
            return events
        try:
            return await self._run_text(turn_id, item.text, events)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            return events

    async def handle_audio(self, item: AudioTurnInput) -> list[ConversationEvent]:
        turn_id, events = self._start(item.request_id, "audio")
        if not turn_id:
            return events
        try:
            raw = base64.b64decode(item.data, validate=True)
            model = self.runtime_models.get("ASR") or {}
            if self._asr is None:
                self._asr = self._asr_factory(model)
            if hasattr(self._asr, "to_playground_text"):
                text = await self._asr.to_playground_text(raw)
            else:
                text, _ = await self._asr.speech_to_text_wrapper([raw], self.claims.conversation_id, raise_errors=True)
            text = str(text or "").strip()
            if not text:
                raise RuntimeError("ASR 未识别到文字")
            self._next(events, "asr.final", turn_id, {"text": text})
            return await self._run_text(turn_id, text, events)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            return events

    async def cancel(self, turn_id: str) -> list[ConversationEvent]:
        self._cancelled_turns.add(turn_id)
        events: list[ConversationEvent] = []
        self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
        return events
