from __future__ import annotations

import asyncio
import base64
import time
import uuid
from collections import deque
from collections.abc import Callable, Mapping
from typing import Any

from .protocol import AudioTurnInput, ConversationEvent, RuntimeTokenClaims, TextTurnInput
from .protocol import MAX_AUDIO_OUTPUT_BYTES, MAX_OUTPUT_TEXT_LENGTH
from .tool_calls import PublicToolCallAccumulator
from .tools import PublicConversationToolRuntime, PublicToolError


MAX_COMPLETED_TURNS = 100


class PublicConversationSession:
    def __init__(
        self,
        claims: RuntimeTokenClaims,
        bundle: Mapping[str, Any],
        *,
        llm_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        asr_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        tts_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        history_loader: Callable[[int], Any] | None = None,
        history_writer: Callable[[dict[str, Any]], Any] | None = None,
        tool_runtime_factory: Callable[[Mapping[str, Any]], Any] | None = None,
    ):
        self.claims = claims
        self.bundle = bundle
        self.runtime_models = bundle.get("runtime_models") or {}
        self._seen_requests: set[str] = set()
        self._cancelled_turns: set[str] = set()
        self._completed_turns: set[str] = set()
        self._active_turns: set[str] = set()
        self._turn_requests: dict[str, str] = {}
        self._cancelled_emitted: set[str] = set()
        self._sequence = 0
        self._llm_factory = llm_factory or self._create_llm
        self._asr_factory = asr_factory or self._create_asr
        self._tts_factory = tts_factory or self._create_tts
        self._memory_factory = self._create_memory
        self._history_loader = history_loader
        self._history_writer = history_writer
        self._tool_runtime_factory = tool_runtime_factory or PublicConversationToolRuntime
        self._llm = None
        self._asr = None
        self._tts = None
        self._memory = None
        self._tool_runtime = None
        self._history: deque[dict[str, Any]] = deque(maxlen=50)

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
        return [self.expired()]

    def expired(self, reason: str = "runtime_token_expired") -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "session.expired", None, {"reason": reason})
        return events[0]

    def ready(self) -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "session.ready", None, {"agent_version": self.claims.agent_version})
        return events[0]

    def stream_ready(self, sample_rate: int = 16000, channels: int = 1) -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "stream.ready", None, {
            "sample_rate": sample_rate, "channels": channels, "format": "pcm_s16le",
        })
        return events[0]

    def _start(self, request_id: str, input_mode: str) -> tuple[str, list[ConversationEvent]]:
        if request_id in self._seen_requests:
            return "", self._duplicate()
        self._seen_requests.add(request_id)
        turn_id = uuid.uuid4().hex
        self._active_turns.add(turn_id)
        self._turn_requests[turn_id] = request_id
        events: list[ConversationEvent] = []
        self._next(events, "turn.started", turn_id, {"request_id": request_id, "input_mode": input_mode})
        return turn_id, events

    def _dialogue(self, text: str, memory_text: str | None = None,
                  skill_prompt: str | None = None) -> list[dict[str, Any]]:
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

    async def _run_text(self, turn_id: str, text: str, events: list[ConversationEvent], emit=None) -> list[ConversationEvent]:
        if turn_id in self._cancelled_turns:
            if turn_id not in self._cancelled_emitted:
                self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
                await self._emit_last(events, emit)
            return events
        model = self.runtime_models.get("LLM") or {}
        if self._llm is None:
            self._llm = self._llm_factory(model)
        memory_text = await self._query_memory(text)
        tool_runtime, tool_turn = await self._select_tool_turn(text)
        skill_prompt = tool_turn.skill.execution_prompt if tool_turn is not None and tool_turn.skill else None
        dialogue = self._dialogue(text, memory_text, skill_prompt)
        schemas = tool_runtime.schemas(tool_turn) if tool_runtime is not None and tool_turn is not None else []
        if schemas:
            visible = await self._run_with_tools(
                turn_id, dialogue, events, emit, tool_runtime, tool_turn, schemas
            )
        else:
            visible = await self._emit_visible_stream(turn_id, dialogue, events, emit)
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
            if len(audio) > MAX_AUDIO_OUTPUT_BYTES:
                raise RuntimeError("TTS 音频超出大小限制")
            self._next(events, "tts.audio", turn_id, {
                "mime_type": mime_type,
                "data": base64.b64encode(audio).decode("ascii"),
                "text": visible,
            })
            await self._emit_last(events, emit)
        self._completed_turns.add(turn_id)
        await self._save_memory(text, visible)
        history_item = {
            "turn_id": turn_id,
            "request_id": self._turn_requests.get(turn_id),
            "source": str((self.bundle.get("config") or {}).get("source", "app")),
            "text": text[:MAX_OUTPUT_TEXT_LENGTH],
            "reply": visible[:MAX_OUTPUT_TEXT_LENGTH],
            "occurred_at": int(time.time() * 1000),
        }
        self._history.append(history_item)
        await self._write_history(history_item)
        self._next(events, "turn.completed", turn_id, {"text": visible})
        await self._emit_last(events, emit)
        return events

    async def _emit_visible_stream(self, turn_id, dialogue, events, emit) -> str:
        visible = ""
        async for chunk in self._stream_llm(dialogue):
            if await self._cancelled(turn_id, events, emit):
                return ""
            value = str(chunk or "")
            if not value:
                continue
            visible += value
            if len(visible) > MAX_OUTPUT_TEXT_LENGTH:
                raise RuntimeError("LLM 输出超出大小限制")
            self._next(events, "llm.delta", turn_id, {"text": value})
            await self._emit_last(events, emit)
        return visible

    async def _run_with_tools(self, turn_id, dialogue, events, emit, runtime, tool_turn, schemas) -> str:
        if not callable(getattr(self._llm, "response_with_functions", None)):
            name = schemas[0].get("function", {}).get("name", "readonly_tool")
            self._next(events, "tool.failed", turn_id, {
                "name": name,
                "code": "provider_unsupported",
                "message": "当前模型不支持实时工具调用",
            })
            await self._emit_last(events, emit)
            message = "当前模型不支持实时工具调用，暂时无法获取实时天气或新闻。"
            self._next(events, "llm.delta", turn_id, {"text": message})
            await self._emit_last(events, emit)
            return message

        for round_index in range(2):
            buffered: list[str] = []
            accumulator = PublicToolCallAccumulator()
            async for content, fragments in self._stream_llm_functions(dialogue, schemas):
                if await self._cancelled(turn_id, events, emit):
                    return ""
                if content:
                    buffered.append(str(content))
                if fragments:
                    accumulator.add(fragments)
            calls = accumulator.finish()
            if not calls:
                visible = ""
                for value in buffered:
                    visible += value
                    if len(visible) > MAX_OUTPUT_TEXT_LENGTH:
                        raise RuntimeError("LLM 输出超出大小限制")
                    self._next(events, "llm.delta", turn_id, {"text": value})
                    await self._emit_last(events, emit)
                return visible
            if round_index >= 1:
                raise RuntimeError("工具调用轮数超出限制")
            dialogue.append({
                "role": "assistant",
                "content": "".join(buffered),
                "tool_calls": [call.assistant_value() for call in calls],
            })
            for call in calls:
                if await self._cancelled(turn_id, events, emit):
                    return ""
                self._next(events, "tool.started", turn_id, {"name": call.name})
                await self._emit_last(events, emit)
                try:
                    result = await runtime.execute(tool_turn, call.name, call.arguments)
                    self._next(events, "tool.completed", turn_id, {
                        "name": result.name,
                        "duration_ms": result.duration_ms,
                    })
                    tool_content = result.content
                except PublicToolError as error:
                    message = self._public_tool_failure_message(error.code)
                    self._next(events, "tool.failed", turn_id, {
                        "name": call.name,
                        "code": error.code,
                        "message": message,
                    })
                    tool_content = (
                        f"实时工具调用失败：{message}。请明确告诉用户当前数据不可用，"
                        "不要猜测或编造实时天气、新闻。"
                    )
                await self._emit_last(events, emit)
                dialogue.append({
                    "role": "tool",
                    "tool_call_id": call.id,
                    "name": call.name,
                    "content": tool_content,
                })
        raise RuntimeError("工具调用未产生最终回复")

    async def _select_tool_turn(self, text):
        try:
            if self._tool_runtime is None:
                self._tool_runtime = self._tool_runtime_factory(self.bundle)
            return self._tool_runtime, await self._tool_runtime.select(text)
        except Exception:
            self._tool_runtime = None
            return None, None

    async def _cancelled(self, turn_id, events, emit) -> bool:
        if turn_id not in self._cancelled_turns:
            return False
        if turn_id not in self._cancelled_emitted:
            self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
            await self._emit_last(events, emit)
        return True

    @staticmethod
    def _public_tool_failure_message(code: str) -> str:
        return {
            "timeout": "实时查询超时，请稍后再试",
            "invalid_arguments": "查询条件无效，请换一种说法",
            "not_allowed": "当前角色未授权此工具",
            "result_too_large": "查询结果过大，暂时无法处理",
        }.get(code, "实时查询暂时不可用")

    async def _stream_llm(self, dialogue):
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue()
        sentinel = object()

        def produce() -> None:
            try:
                for chunk in self._llm.response(self.claims.conversation_id, dialogue):
                    loop.call_soon_threadsafe(queue.put_nowait, ("chunk", chunk))
            except Exception as error:
                loop.call_soon_threadsafe(queue.put_nowait, ("error", error))
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, ("done", sentinel))

        worker = asyncio.create_task(asyncio.to_thread(produce))
        try:
            while True:
                kind, value = await queue.get()
                if kind == "chunk":
                    yield value
                elif kind == "error":
                    raise value
                else:
                    break
        finally:
            if not worker.done():
                worker.cancel()

    async def _stream_llm_functions(self, dialogue, schemas):
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue()
        sentinel = object()

        def produce() -> None:
            try:
                for value in self._llm.response_with_functions(
                    self.claims.conversation_id, dialogue, functions=schemas
                ):
                    loop.call_soon_threadsafe(queue.put_nowait, ("chunk", value))
            except Exception as error:
                loop.call_soon_threadsafe(queue.put_nowait, ("error", error))
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, ("done", sentinel))

        worker = asyncio.create_task(asyncio.to_thread(produce))
        try:
            while True:
                kind, value = await queue.get()
                if kind == "chunk":
                    if isinstance(value, tuple) and len(value) == 2:
                        yield value
                    elif isinstance(value, dict):
                        yield value.get("content"), value.get("tool_calls")
                    else:
                        raise RuntimeError("LLM 工具响应格式无效")
                elif kind == "error":
                    raise value
                else:
                    break
        finally:
            if not worker.done():
                worker.cancel()

    async def _emit_last(self, events, emit) -> None:
        if emit is not None and events:
            await emit(events[-1])

    async def _query_memory(self, text: str) -> str | None:
        try:
            config = self.bundle.get("config") or {}
            if config.get("memoryEnabled") is False or config.get("memory_enabled") is False:
                return None
            if self._memory is None and "Memory" in self.runtime_models:
                self._memory = self._memory_factory(self.runtime_models["Memory"])
                initializer = getattr(self._memory, "init_memory", None)
                if callable(initializer):
                    initializer(
                        memory_namespace=str((self.bundle.get("config") or {}).get(
                            "profileMemoryNamespace",
                            (self.bundle.get("config") or {}).get(
                                "memoryNamespace", self.claims.conversation_id))),
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
        config = self.bundle.get("config") or {}
        if self._memory is None or config.get("memoryEnabled") is False or config.get("memory_enabled") is False:
            return
        try:
            await self._memory.save_memory(
                [{"role": "user", "content": text}, {"role": "assistant", "content": reply}],
                session_id=self.claims.conversation_id,
            )
        except Exception:
            self._memory = None

    async def handle_text(self, item: TextTurnInput) -> list[ConversationEvent]:
        turn_id, events = self.begin_text(item)
        if not turn_id:
            return events
        return await self.finish_text(turn_id, item.text, events)

    def begin_text(self, item: TextTurnInput) -> tuple[str, list[ConversationEvent]]:
        if self._expired():
            return "", self._session_expired()
        if len(self._completed_turns) >= MAX_COMPLETED_TURNS:
            return "", [self._event("error", self._next_error_sequence(), None,
                                     {"code": "turn_quota_exceeded", "message": "会话轮次已达到上限", "retryable": False})]
        if "text" not in self.claims.input_modes:
            return "", [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "文本输入未授权", "retryable": False})]
        return self._start(item.request_id, "text")

    async def finish_text(self, turn_id: str, text: str, events: list[ConversationEvent], emit=None) -> list[ConversationEvent]:
        if not turn_id:
            return events
        try:
            return await self._run_text(turn_id, text, events, emit)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            await self._emit_last(events, emit)
            return events
        finally:
            self._active_turns.discard(turn_id)

    async def handle_audio(self, item: AudioTurnInput) -> list[ConversationEvent]:
        turn_id, events = self.begin_audio(item)
        if not turn_id:
            return events
        return await self.finish_audio(turn_id, item, events)

    def begin_audio(self, item: AudioTurnInput) -> tuple[str, list[ConversationEvent]]:
        if self._expired():
            return "", self._session_expired()
        if len(self._completed_turns) >= MAX_COMPLETED_TURNS:
            return "", [self._event("error", self._next_error_sequence(), None,
                                     {"code": "turn_quota_exceeded", "message": "会话轮次已达到上限", "retryable": False})]
        if "audio" not in self.claims.input_modes:
            return "", [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "音频输入未授权", "retryable": False})]
        return self._start(item.request_id, "audio")

    async def finish_audio(self, turn_id: str, item: AudioTurnInput, events: list[ConversationEvent], emit=None) -> list[ConversationEvent]:
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
            await self._emit_last(events, emit)
            return await self._run_text(turn_id, text, events, emit)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            await self._emit_last(events, emit)
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
        self._cancelled_emitted.add(turn_id)
        events: list[ConversationEvent] = []
        self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
        return events

    def _next_error_sequence(self) -> int:
        self._sequence += 1
        return self._sequence

    def history(self, limit: int = 20) -> ConversationEvent:
        safe_limit = min(max(int(limit), 1), 50)
        events: list[ConversationEvent] = []
        self._next(events, "conversation.history", None, {"items": list(self._history)[-safe_limit:]})
        return events[0]

    def failure(self, turn_id: str, code: str, message: str, *, retryable: bool = True) -> ConversationEvent:
        self._active_turns.discard(turn_id)
        events: list[ConversationEvent] = []
        self._next(events, "error", turn_id, {"code": code, "message": message, "retryable": retryable})
        return events[0]

    async def _write_history(self, item: dict[str, Any]) -> None:
        if self._history_writer is None:
            return
        try:
            result = self._history_writer(item)
            if hasattr(result, "__await__"):
                await result
        except Exception:
            return

    async def history_async(self, limit: int = 20) -> ConversationEvent:
        safe_limit = min(max(int(limit), 1), 50)
        if self._history_loader is not None:
            try:
                result = self._history_loader(safe_limit)
                if hasattr(result, "__await__"):
                    result = await result
                if isinstance(result, list):
                    events: list[ConversationEvent] = []
                    self._next(events, "conversation.history", None, {"items": result[-safe_limit:]})
                    return events[0]
            except Exception:
                pass
        return self.history(safe_limit)
