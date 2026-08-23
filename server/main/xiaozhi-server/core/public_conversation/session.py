from __future__ import annotations

import asyncio
import base64
import time
import uuid
from collections.abc import Callable, Mapping
from typing import Any

from core.capabilities.models import CapabilityBundle
from core.capabilities.runtime import SkillTurnRuntime

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
        self._completed_turns: set[str] = set()
        self._active_turns: set[str] = set()
        self._sequence = 0
        self._llm_factory = llm_factory or self._create_llm
        self._asr_factory = asr_factory or self._create_asr
        self._tts_factory = tts_factory or self._create_tts
        self._memory_factory = self._create_memory
        self._llm = None
        self._asr = None
        self._tts = None
        self._memory = None

    def _create_llm(self, model: Mapping[str, Any]) -> Any:
        from core.utils import llm

        return llm.create_instance(str(model["type"]), dict(model))

    def _create_asr(self, model: Mapping[str, Any]) -> Any:
        from core.utils import asr

        return asr.create_instance(str(model["type"]), dict(model), True)

    def _create_tts(self, model: Mapping[str, Any]) -> Any:
        from core.utils import tts

        return tts.create_instance(str(model["type"]), dict(model), True)

    def _create_memory(self, model: Mapping[str, Any]) -> Any:
        from core.utils import memory

        memory_type = model.get("type") or model.get("id")
        if not memory_type:
            raise RuntimeError("Memory provider type is missing")
        return memory.create_instance(
            str(memory_type), dict(model), (self.bundle.get("config") or {}).get("summaryMemory")
        )

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

    def _expired(self) -> bool:
        return int(time.time()) >= self.claims.expires_at

    def _session_expired(self) -> list[ConversationEvent]:
        events: list[ConversationEvent] = []
        self._next(events, "session.expired", None, {"reason": "runtime_token_expired"})
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
        self._active_turns.add(turn_id)
        events: list[ConversationEvent] = []
        self._next(events, "turn.started", turn_id, {"request_id": request_id, "input_mode": input_mode})
        return turn_id, events

    def _dialogue(self, text: str, memory_text: str | None = None,
                  skill_prompt: str | None = None) -> list[dict[str, str]]:
        config = self.bundle.get("config") or {}
        prompt = str(config.get("systemPrompt") or "")
        role_prompt = str(config.get("rolePrompt") or "")
        if role_prompt:
            prompt = f"{prompt}\n\n{role_prompt}".strip()
        if memory_text and memory_text.strip():
            prompt = f"{prompt}\n\n<memory>\n{memory_text.strip()}\n</memory>".strip()
        if skill_prompt and skill_prompt.strip():
            prompt = f"{prompt}\n\n<skill_execution>\n{skill_prompt.strip()}\n</skill_execution>".strip()
        messages = [{"role": "user", "content": text}]
        return ([{"role": "system", "content": prompt}] if prompt else []) + messages

    async def _run_text(self, turn_id: str, text: str, events: list[ConversationEvent]) -> list[ConversationEvent]:
        if turn_id in self._cancelled_turns:
            self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
            return events
        model = self.runtime_models.get("LLM") or {}
        if self._llm is None:
            self._llm = self._llm_factory(model)
        memory_text = await self._query_memory(text)
        skill_prompt = await self._skill_execution_prompt(text)
        chunks = await asyncio.to_thread(
            lambda: list(self._llm.response(
                self.claims.conversation_id, self._dialogue(text, memory_text, skill_prompt)
            ))
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
        if "audio" in self.claims.output_modes:
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
        self._completed_turns.add(turn_id)
        await self._save_memory(text, visible)
        self._next(events, "turn.completed", turn_id, {"text": visible})
        return events

    async def _query_memory(self, text: str) -> str | None:
        try:
            if self._memory is None and "Memory" in self.runtime_models:
                self._memory = self._memory_factory(self.runtime_models["Memory"])
                initializer = getattr(self._memory, "init_memory", None)
                if callable(initializer):
                    initializer(
                        memory_namespace=str((self.bundle.get("config") or {}).get(
                            "memoryNamespace", self.claims.conversation_id)),
                        llm=self._llm,
                        summary_memory=(self.bundle.get("config") or {}).get("summaryMemory"),
                        save_to_file=False,
                        source_metadata={
                            "source_conversation_id": self.claims.conversation_id,
                            "source_subject": self.claims.subject,
                            "source_agent_id": self.claims.agent_id,
                        },
                    )
            if self._memory is None:
                return None
            result = await self._memory.query_memory(text)
            return str(result or "")
        except Exception:
            self._memory = None
            return None

    async def _save_memory(self, text: str, reply: str) -> None:
        if self._memory is None:
            return
        try:
            await self._memory.save_memory(
                [{"role": "user", "content": text}, {"role": "assistant", "content": reply}],
                session_id=self.claims.conversation_id,
            )
        except Exception:
            self._memory = None

    async def _skill_execution_prompt(self, text: str) -> str | None:
        raw_skills = (self.bundle.get("config") or {}).get("skills")
        if not isinstance(raw_skills, list) or not raw_skills:
            return None
        try:
            bundle = CapabilityBundle.parse({
                "deviceId": self.claims.conversation_id,
                "configVersion": 0,
                "skills": raw_skills,
                "tools": {},
            })
            turn = await SkillTurnRuntime().select(bundle, text, None, tools_enabled=True)
            return turn.skill.execution_prompt if turn.skill is not None else None
        except Exception:
            return None

    async def handle_text(self, item: TextTurnInput) -> list[ConversationEvent]:
        if self._expired():
            return self._session_expired()
        if "text" not in self.claims.input_modes:
            return [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "文本输入未授权", "retryable": False})]
        turn_id, events = self._start(item.request_id, "text")
        if not turn_id:
            return events
        try:
            return await self._run_text(turn_id, item.text, events)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            return events
        finally:
            self._active_turns.discard(turn_id)

    async def handle_audio(self, item: AudioTurnInput) -> list[ConversationEvent]:
        if self._expired():
            return self._session_expired()
        if "audio" not in self.claims.input_modes:
            return [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "音频输入未授权", "retryable": False})]
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
        finally:
            self._active_turns.discard(turn_id)

    async def cancel(self, turn_id: str) -> list[ConversationEvent]:
        if not isinstance(turn_id, str) or not turn_id:
            raise ValueError("turn_id is required")
        if turn_id in self._cancelled_turns or turn_id in self._completed_turns:
            return self._duplicate()
        if turn_id not in self._active_turns:
            events: list[ConversationEvent] = []
            self._next(events, "error", None, {
                "code": "unknown_turn",
                "message": "只能取消当前活跃轮次",
                "retryable": False,
            })
            return events
        self._cancelled_turns.add(turn_id)
        events: list[ConversationEvent] = []
        self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
        return events

    def _next_error_sequence(self) -> int:
        self._sequence += 1
        return self._sequence
