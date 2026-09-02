from __future__ import annotations

import asyncio
import base64
import inspect
import time
import uuid
from collections import deque
from collections.abc import Callable, Mapping
from types import SimpleNamespace
from typing import Any

from core.companion.identity import is_profile_memory_namespace
from core.utils.dialogue import Message

from .protocol import (
    AudioTurnInput,
    ConversationEvent,
    RuntimeTokenClaims,
    TextTurnInput,
    WEB_REALTIME_PROTOCOL_VERSION,
)
from .protocol import MAX_AUDIO_OUTPUT_BYTES, MAX_OUTPUT_TEXT_LENGTH, MAX_TEXT_LENGTH
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
        self._cancelled_emitted: set[str] = set()
        self._turn_tasks: dict[str, asyncio.Task[Any]] = {}
        self._turn_requests: dict[str, str] = {}
        self._turn_segments: dict[str, str | None] = {}
        self._turn_events: dict[str, str | None] = {}
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
        self._closed = False

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
        config = self.bundle.get("config") or {}
        profile_namespace = config.get("profileMemoryNamespace") or config.get(
            "profile_memory_namespace"
        )
        summary_memory = None if is_profile_memory_namespace(profile_namespace) else config.get(
            "summaryMemory"
        )
        return memory.create_instance(
            str(memory_type), dict(model), summary_memory
        )

    def _memory_namespace(self, config: Mapping[str, Any]) -> str:
        """Resolve the profile namespace while rejecting cross-owner bundles."""
        profile_namespace = config.get("profileMemoryNamespace")
        if profile_namespace is None:
            profile_namespace = config.get("profile_memory_namespace")
        if profile_namespace is None:
            legacy_namespace = (
                config.get("memoryNamespace")
                or config.get("memory_namespace")
                or self.claims.conversation_id
            )
            if is_profile_memory_namespace(legacy_namespace):
                expected = f"companion:{str(self.claims.subject).strip()}:{self.claims.agent_id}"
                if legacy_namespace.strip() != expected:
                    raise ValueError("profile memory namespace does not match runtime identity")
                return legacy_namespace.strip()
            return str(legacy_namespace)
        if not isinstance(profile_namespace, str) or not profile_namespace.strip():
            raise ValueError("profile memory namespace is invalid")
        expected = f"companion:{str(self.claims.subject).strip()}:{self.claims.agent_id}"
        namespace = profile_namespace.strip()
        if not is_profile_memory_namespace(namespace) or namespace != expected:
            raise ValueError("profile memory namespace does not match runtime identity")
        return namespace

    def _event(self, event_type: str, sequence: int, turn_id: str | None, details: Mapping[str, Any] | None = None,
               *, request_id: str | None = None, segment_id: str | None = None,
               event_id: str | None = None) -> ConversationEvent:
        if turn_id is not None:
            request_id = request_id or self._turn_requests.get(turn_id)
            segment_id = segment_id or self._turn_segments.get(turn_id)
            event_id = event_id or self._turn_events.get(turn_id)
        return ConversationEvent(
            event_type=event_type,
            conversation_id=self.claims.conversation_id,
            turn_id=turn_id,
            sequence=sequence,
            occurred_at=int(time.time() * 1000),
            details=details or {},
            request_id=request_id,
            segment_id=segment_id,
            event_id=event_id,
        )

    def _next(self, events: list[ConversationEvent], event_type: str, turn_id: str | None,
              details: Mapping[str, Any] | None = None, *, request_id: str | None = None,
              segment_id: str | None = None, event_id: str | None = None) -> None:
        self._sequence += 1
        events.append(self._event(event_type, self._sequence, turn_id, details,
                                  request_id=request_id, segment_id=segment_id, event_id=event_id))

    def _duplicate(self, request_id: str | None = None, segment_id: str | None = None,
                   event_id: str | None = None) -> list[ConversationEvent]:
        events: list[ConversationEvent] = []
        details = {"code": "duplicate_request", "message": "request_id 已处理", "retryable": False}
        self._next(events, "error", None, details, request_id=request_id,
                   segment_id=segment_id, event_id=event_id)
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
        self._next(events, "session.ready", None, {
            "agent_version": self.claims.agent_version,
            "protocol_version": WEB_REALTIME_PROTOCOL_VERSION,
            "input_modes": list(self.claims.input_modes),
            "output_modes": list(self.claims.output_modes),
            "expires_at": self.claims.expires_at,
            "supports": {"continuous_audio": "audio" in self.claims.input_modes,
                          "interruption": True, "heartbeat": True},
        })
        return events[0]

    def stream_ready(self, sample_rate: int = 16000, channels: int = 1) -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "stream.ready", None, {
            "sample_rate": sample_rate, "channels": channels, "format": "pcm_s16le",
        })
        return events[0]

    def heartbeat(self, last_sequence: int | None = None) -> ConversationEvent:
        """Acknowledge a client keep-alive without extending token expiry."""
        details: dict[str, Any] = {"last_sequence": self._sequence}
        if isinstance(last_sequence, int) and not isinstance(last_sequence, bool):
            details["acknowledged_sequence"] = last_sequence
        events: list[ConversationEvent] = []
        self._next(events, "session.pong", None, details)
        return events[0]

    def _start(self, request_id: str, input_mode: str, segment_id: str | None = None,
               event_id: str | None = None) -> tuple[str, list[ConversationEvent]]:
        if request_id in self._seen_requests:
            return "", self._duplicate(request_id, segment_id, event_id)
        self._seen_requests.add(request_id)
        turn_id = uuid.uuid4().hex
        self._active_turns.add(turn_id)
        self._turn_requests[turn_id] = request_id
        self._turn_segments[turn_id] = segment_id
        self._turn_events[turn_id] = event_id
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
        messages: list[dict[str, Any]] = []
        raw_history = config.get("history")
        if isinstance(raw_history, list):
            # Durable continuation bundles contain paired user/reply text.
            # Rebuild bounded text fields only; caller-provided roles and tool
            # payloads are deliberately ignored.
            for item in raw_history[-50:]:
                if not isinstance(item, Mapping):
                    continue
                previous_text = item.get("content") or item.get("text") or item.get("user_text")
                previous_reply = item.get("reply") or item.get("assistant_text")
                if isinstance(previous_text, str) and previous_text.strip():
                    messages.append({
                        "role": "user",
                        "content": previous_text.strip()[:MAX_TEXT_LENGTH],
                    })
                if isinstance(previous_reply, str) and previous_reply.strip():
                    messages.append({
                        "role": "assistant",
                        "content": previous_reply.strip()[:MAX_OUTPUT_TEXT_LENGTH],
                    })
        messages.append({"role": "user", "content": text})
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
            if turn_id in self._cancelled_turns:
                return events
            raise RuntimeError("LLM 未返回文本")
        if turn_id in self._cancelled_turns:
            return events
        if "audio" in self.claims.output_modes:
            model = self.runtime_models.get("TTS") or {}
            if self._tts is None:
                self._tts = self._tts_factory(model)
            audio, mime_type = await self._synthesize_public_audio(visible, model)
            if turn_id in self._cancelled_turns:
                return events
            if not isinstance(audio, bytes) or not audio:
                raise RuntimeError("TTS 未返回音频")
            if len(audio) > MAX_AUDIO_OUTPUT_BYTES:
                raise RuntimeError("TTS 音频超出大小限制")
            self._next(events, "tts.audio", turn_id, {
                "mime_type": mime_type,
                "data": base64.b64encode(audio).decode("ascii"),
                **({"text": visible} if self._allows_output("text") else {}),
            })
            await self._emit_last(events, emit)
        self._completed_turns.add(turn_id)
        await self._save_memory(text, visible)
        history_item = {
            "turn_id": turn_id,
            "request_id": self._turn_requests.get(turn_id),
            "source": "app",
            "text": text[:MAX_OUTPUT_TEXT_LENGTH],
            "reply": visible[:MAX_OUTPUT_TEXT_LENGTH],
            "occurred_at": int(time.time() * 1000),
        }
        self._history.append(history_item)
        await self._write_history(history_item)
        completed_details = {"text": visible} if self._allows_output("text") else {}
        self._next(events, "turn.completed", turn_id, completed_details)
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
            if self._allows_output("text"):
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
            if self._allows_output("text"):
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
                    if self._allows_output("text"):
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

    def _allows_output(self, mode: str) -> bool:
        return mode in set(self.claims.output_modes or ())

    async def _synthesize_public_audio(self, text: str, model: Mapping[str, Any]):
        """Run legacy TTS adapters off-loop and return playable bytes plus MIME."""
        provider = self._tts
        self._prepare_public_tts_provider(provider, model)
        playground_wav = getattr(provider, "to_playground_wav", None)
        if callable(playground_wav):
            audio = await asyncio.to_thread(playground_wav, text)
            if isinstance(audio, (bytes, bytearray)) and audio:
                return bytes(audio), "audio/wav"

        def invoke_provider():
            result = provider.text_to_speak(text, None)
            if inspect.isawaitable(result):
                return asyncio.run(result)
            return result

        try:
            audio = await asyncio.to_thread(invoke_provider)
        except Exception as error:
            audio = None
            provider_error = error
        else:
            provider_error = None
        if audio is None:
            # Stream-oriented providers expose a synchronous complete-audio
            # helper for non-device callers. Use it when available rather than
            # pretending that a None result is a playable payload.
            complete = getattr(provider, "to_tts", None)
            if callable(complete):
                try:
                    audio = await asyncio.to_thread(complete, text)
                except Exception as error:
                    raise RuntimeError("TTS provider does not support public audio") from (provider_error or error)
                if isinstance(audio, (list, tuple)):
                    try:
                        from core.utils.util import opus_datas_to_wav_bytes

                        audio = await asyncio.to_thread(
                            opus_datas_to_wav_bytes,
                            audio,
                            self._public_audio_sample_rate(provider, model),
                            self._public_audio_channels(provider, model),
                        )
                    except Exception as error:
                        raise RuntimeError("TTS provider returned an invalid audio stream") from error
        if not isinstance(audio, (bytes, bytearray)) or not audio:
            raise RuntimeError("TTS provider did not return public audio")
        return self._normalize_public_audio(bytes(audio), provider, model)

    def _prepare_public_tts_provider(self, provider: Any, model: Mapping[str, Any]) -> None:
        """Give legacy providers the small connection context they expect."""
        if provider is None or getattr(provider, "conn", None) is not None:
            return
        sample_rate = model.get("sample_rate") if isinstance(model, Mapping) else None
        if sample_rate is None and isinstance(model, Mapping):
            params = model.get("audio_params")
            if isinstance(params, Mapping):
                sample_rate = params.get("sample_rate")
        config = self.bundle.get("config") or {}
        if sample_rate is None and isinstance(config, Mapping):
            sample_rate = config.get("sample_rate")
            if sample_rate is None and isinstance(config.get("audio_params"), Mapping):
                sample_rate = config["audio_params"].get("sample_rate")
        try:
            sample_rate = max(8000, int(sample_rate or 24000))
        except (TypeError, ValueError):
            sample_rate = 24000
        provider.conn = SimpleNamespace(
            sample_rate=sample_rate,
            sentence_id=self.claims.conversation_id,
            session_id=self.claims.conversation_id,
            stop_event=SimpleNamespace(is_set=lambda: False),
            client_abort=False,
        )

    @staticmethod
    def _normalize_public_audio(audio: bytes, provider: Any, model: Mapping[str, Any]):
        detected = PublicConversationSession._detect_audio_mime(audio)
        if detected is not None:
            return audio, detected
        format_name = str(
            getattr(provider, "audio_file_type", None)
            or model.get("format", "")
        ).strip().lower()
        mime = {
            "mp3": "audio/mpeg",
            "mpeg": "audio/mpeg",
            "wav": "audio/wav",
            "wave": "audio/wav",
            "ogg": "audio/ogg",
            "opus": "audio/opus",
            "webm": "audio/webm",
            "flac": "audio/flac",
        }.get(format_name)
        if mime is not None:
            if mime == "audio/pcm":
                return PublicConversationSession._pcm_as_wav(audio, provider, model)
            return audio, mime
        if format_name in {"pcm", "raw", "s16le"}:
            return PublicConversationSession._pcm_as_wav(audio, provider, model)
        raise RuntimeError("TTS 音频格式无法识别")

    @staticmethod
    def _public_audio_sample_rate(provider: Any, model: Mapping[str, Any]) -> int:
        encoder = getattr(provider, "opus_encoder", None)
        candidates = [
            getattr(encoder, "sample_rate", None),
            getattr(provider, "sample_rate", None),
            getattr(getattr(provider, "conn", None), "sample_rate", None),
        ]
        if isinstance(model, Mapping):
            candidates.append(model.get("sample_rate"))
            for key in ("audio_params", "audio_setting"):
                params = model.get(key)
                if isinstance(params, Mapping):
                    candidates.append(params.get("sample_rate"))
        for value in candidates:
            try:
                parsed = int(value)
            except (TypeError, ValueError):
                continue
            if 8000 <= parsed <= 48000:
                return parsed
        return 16000

    @staticmethod
    def _public_audio_channels(provider: Any, model: Mapping[str, Any]) -> int:
        encoder = getattr(provider, "opus_encoder", None)
        candidates = [getattr(encoder, "channels", None)]
        if isinstance(model, Mapping):
            candidates.append(model.get("channels"))
            for key in ("audio_params", "audio_setting"):
                params = model.get(key)
                if isinstance(params, Mapping):
                    candidates.append(params.get("channels") or params.get("channel"))
        for value in candidates:
            try:
                parsed = int(value)
            except (TypeError, ValueError):
                continue
            if parsed in (1, 2):
                return parsed
        return 1

    @staticmethod
    def _detect_audio_mime(audio: bytes) -> str | None:
        if audio.startswith(b"RIFF") and audio[8:12] == b"WAVE":
            return "audio/wav"
        if audio.startswith(b"ID3") or (
            len(audio) >= 2 and audio[0] == 0xFF and (audio[1] & 0xE0) == 0xE0
        ):
            return "audio/mpeg"
        if audio.startswith(b"OggS"):
            return "audio/ogg"
        if audio.startswith(b"fLaC"):
            return "audio/flac"
        if audio.startswith(b"\x1a\x45\xdf\xa3"):
            return "audio/webm"
        return None

    @staticmethod
    def _pcm_as_wav(audio: bytes, provider: Any, model: Mapping[str, Any]):
        import io
        import wave

        sample_rate = getattr(provider, "sample_rate", None)
        if sample_rate is None:
            audio_params = model.get("audio_params") if isinstance(model, Mapping) else None
            sample_rate = audio_params.get("sample_rate") if isinstance(audio_params, Mapping) else None
            if sample_rate is None and isinstance(model, Mapping):
                sample_rate = model.get("sample_rate")
        try:
            sample_rate = max(8000, int(sample_rate or 24000))
        except (TypeError, ValueError):
            sample_rate = 24000
        output = io.BytesIO()
        with wave.open(output, "wb") as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2)
            wav_file.setframerate(sample_rate)
            wav_file.writeframes(audio)
        return output.getvalue(), "audio/wav"

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
        if not self._closed and emit is not None and events:
            await emit(events[-1])

    async def _query_memory(self, text: str) -> str | None:
        try:
            config = self.bundle.get("config") or {}
            if self._memory_disabled(config):
                return None
            if self._memory is None and "Memory" in self.runtime_models:
                memory_namespace = self._memory_namespace(config)
                self._memory = self._memory_factory(self.runtime_models["Memory"])
                initializer = getattr(self._memory, "init_memory", None)
                if callable(initializer):
                    summary_memory = (
                        None
                        if is_profile_memory_namespace(memory_namespace)
                        else config.get("summaryMemory")
                    )
                    initializer(
                        memory_namespace=memory_namespace,
                        llm=self._llm,
                        summary_memory=summary_memory,
                        # Profile-scoped local memory is shared with hardware
                        # connections and the profile management endpoint via
                        # the server-side namespace file. Conversation-scoped
                        # legacy memory keeps its external summary path.
                        save_to_file=is_profile_memory_namespace(memory_namespace),
                        source_metadata={
                            "source_user_id": self.claims.subject,
                            "source_profile_id": self.claims.agent_id,
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
        if self._memory is None or self._memory_disabled(config):
            return
        try:
            await self._memory.save_memory(
                [
                    Message(role="user", content=text),
                    Message(role="assistant", content=reply),
                ],
                session_id=self.claims.conversation_id,
            )
        except Exception:
            self._memory = None

    def _memory_disabled(self, config: Mapping[str, Any]) -> bool:
        """Treat all supported false-like flags and no-memory providers alike."""
        for key in ("memoryEnabled", "memory_enabled"):
            if key not in config:
                continue
            value = config.get(key)
            if isinstance(value, bool):
                if not value:
                    return True
            elif isinstance(value, (int, float)) and not isinstance(value, bool):
                if value == 0:
                    return True
            elif isinstance(value, str) and value.strip().lower() in {"0", "false", "off", "no"}:
                return True
        model = self.runtime_models.get("Memory") or self.runtime_models.get("memory") or {}
        if isinstance(model, Mapping):
            provider_type = model.get("type") or model.get("id")
        else:
            provider_type = model
        return str(provider_type or "").strip().lower() in {
            "nomem", "memory_nomem", "mem_report_only", "memory_mem_report_only"
        }

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
        return self._start(item.request_id, "text", item.segment_id, item.event_id)

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
            self._cleanup_turn(turn_id)

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
        return self._start(item.request_id, "audio", item.segment_id, item.event_id)

    def begin_audio_transcript(self, request_id: str, text: str,
                               segment_id: str | None = None,
                               event_id: str | None = None) -> tuple[str, list[ConversationEvent]]:
        """Start an audio turn when streaming ASR already produced its final text.

        This avoids re-running the batch ASR provider and keeps authorization tied
        to the audio input scope even though the downstream LLM receives text.
        """
        if self._expired():
            return "", self._session_expired()
        if len(self._completed_turns) >= MAX_COMPLETED_TURNS:
            return "", [self._event("error", self._next_error_sequence(), None,
                                     {"code": "turn_quota_exceeded", "message": "会话轮次已达到上限", "retryable": False})]
        if "audio" not in self.claims.input_modes:
            return "", [self._event("error", self._next_error_sequence(), None,
                                     {"code": "input_mode_not_allowed", "message": "音频输入未授权", "retryable": False})]
        if not isinstance(text, str) or not text.strip():
            raise ValueError("audio transcript is required")
        return self._start(request_id, "audio", segment_id, event_id)

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
            if self._allows_output("text"):
                self._next(events, "asr.final", turn_id, {"text": text})
                await self._emit_last(events, emit)
            return await self._run_text(turn_id, text, events, emit)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            await self._emit_last(events, emit)
            return events
        finally:
            self._active_turns.discard(turn_id)
            self._cleanup_turn(turn_id)

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
        self._active_turns.discard(turn_id)
        task = self._turn_tasks.pop(turn_id, None)
        if task is not None and not task.done() and task is not asyncio.current_task():
            task.cancel()
        events: list[ConversationEvent] = []
        self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
        return events

    def attach_task(self, turn_id: str, task: asyncio.Task[Any]) -> None:
        """Associate a running provider pipeline so cancellation can stop it."""
        if not turn_id:
            return
        if self._closed:
            if not task.done():
                task.cancel()
            return
        self._turn_tasks[turn_id] = task

        def clear_done(_task: asyncio.Task[Any]) -> None:
            current = self._turn_tasks.get(turn_id)
            if current is _task:
                self._turn_tasks.pop(turn_id, None)

        task.add_done_callback(clear_done)

    def _cleanup_turn(self, turn_id: str) -> None:
        self._turn_tasks.pop(turn_id, None)
        self._turn_requests.pop(turn_id, None)
        self._turn_segments.pop(turn_id, None)
        self._turn_events.pop(turn_id, None)

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
        self._cleanup_turn(turn_id)
        return events[0]

    async def _write_history(self, item: dict[str, Any]) -> None:
        if self._closed or self._history_writer is None:
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

    async def aclose(self) -> None:
        """Stop outstanding turns and release provider-owned transports.

        The HTTP handler owns the socket, but providers may own their own HTTP
        clients or WebSockets. Closing them here keeps repeated mobile runtime
        connections from accumulating resources in the Python process.
        """
        if self._closed:
            return
        self._closed = True
        current = asyncio.current_task()
        tasks = [
            task
            for task in self._turn_tasks.values()
            if task is not current and not task.done()
        ]
        self._turn_tasks.clear()
        self._active_turns.clear()
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

        resources = (self._asr, self._tts, self._llm, self._memory, self._tool_runtime)
        seen: set[int] = set()
        for resource in resources:
            if resource is None or id(resource) in seen:
                continue
            seen.add(id(resource))
            for method_name in ("aclose", "close"):
                method = getattr(resource, method_name, None)
                if not callable(method):
                    continue
                try:
                    result = method()
                    if inspect.isawaitable(result):
                        await result
                except Exception:
                    pass
                break
        self._asr = None
        self._tts = None
        self._llm = None
        self._memory = None
        self._tool_runtime = None

    async def close(self) -> None:
        """Compatibility alias used by provider/handler integrations."""
        await self.aclose()
