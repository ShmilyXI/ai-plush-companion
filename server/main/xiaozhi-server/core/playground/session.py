from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .protocol import PlaygroundEvent, PlaygroundInput, PlaygroundSnapshot
import asyncio
import base64
import tempfile
from pathlib import Path
import mimetypes


@dataclass
class PlaygroundSession:
    snapshot: PlaygroundSnapshot
    runtime_models: dict[str, dict[str, Any]] = field(default_factory=dict)
    events: list[PlaygroundEvent] = field(default_factory=list)
    _next_input_sequence: int = 1
    _next_event_sequence: int = 1

    def _event_sequence(self) -> int:
        sequence = self._next_event_sequence
        self._next_event_sequence += 1
        return sequence

    def _llm(self):
        model = self.runtime_models.get("LLM") or {}
        config = dict(model.get("config") or {})
        provider = config.get("type")
        if not provider:
            return None
        from core.utils import llm
        return llm.create_instance(provider, config)

    def _tts(self):
        model = self.runtime_models.get("TTS") or {}
        config = dict(model.get("config") or {})
        provider = config.get("type")
        if not provider:
            return None
        from core.utils import tts
        return tts.create_instance(provider, config, True)

    def _vllm(self):
        model = self.runtime_models.get("VLLM") or {}
        config = dict(model.get("config") or {})
        provider = config.get("type")
        if not provider:
            return None
        from core.utils import vllm
        return vllm.create_instance(provider, config)

    def _asr(self):
        model = self.runtime_models.get("ASR") or {}
        config = dict(model.get("config") or {})
        provider = config.get("type")
        if not provider:
            return None
        from core.utils import asr
        return asr.create_instance(provider, config, True)

    async def _synthesize(self, text: str) -> tuple[str, bytes]:
        provider = self._tts()
        if provider is None:
            raise RuntimeError("当前角色没有可用的 TTS 模型")
        if hasattr(provider, "to_playground_wav"):
            data = await asyncio.to_thread(provider.to_playground_wav, text)
            if not data:
                raise RuntimeError("TTS 未返回音频数据")
            return "audio/wav", data
        result = await provider.text_to_speak(text, None)
        if isinstance(result, bytes) and result:
            file_type = str(getattr(provider, "audio_file_type", "wav")).lower()
            return mimetypes.types_map.get(f".{file_type}", f"audio/{file_type}"), result
        suffix = f".{str(getattr(provider, 'audio_file_type', 'wav')).lower()}"
        output_path = str(Path(tempfile.gettempdir()) / f"playground-{self.snapshot.session_id}-{self._next_event_sequence}{suffix}")
        await provider.text_to_speak(text, output_path)
        path = Path(output_path)
        if not path.exists() or path.stat().st_size == 0:
            raise RuntimeError("TTS 未生成可播放音频")
        data = path.read_bytes()
        path.unlink(missing_ok=True)
        return mimetypes.types_map.get(suffix, f"audio/{suffix[1:]}") , data

    async def execute(self, item: PlaygroundInput) -> list[PlaygroundEvent]:
        generated: list[PlaygroundEvent] = []
        if item.kind != "text":
            if item.kind == "audio":
                provider = self._asr()
                if provider is None:
                    return generated
                sequence = self._event_sequence()
                started = __import__("time").time_ns() // 1_000_000
                try:
                    raw = str(item.value)
                    encoded = raw.split(",", 1)[1] if "," in raw else raw
                    pcm = base64.b64decode(encoded)
                    text, _ = await provider.speech_to_text_wrapper([pcm], self.snapshot.session_id, raise_errors=True)
                    transcript = str(text or "")[:400]
                    if not transcript:
                        raise RuntimeError("ASR 未识别到文字")
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "asr", "recognition", "completed", started,
                                                      __import__("time").time_ns() // 1_000_000, 0, "音频输入", transcript, None, {"transcript": transcript}))
                except Exception as exc:
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "asr", "recognition", "failed", started,
                                                      __import__("time").time_ns() // 1_000_000, 0, "音频输入", "", str(exc)[:400]))
                self.events.extend(generated)
                return generated
            if item.kind == "tts":
                sequence = self._event_sequence()
                started = __import__("time").time_ns() // 1_000_000
                try:
                    mime_type, audio_bytes = await self._synthesize(str(item.value))
                    audio_data_url = f"data:{mime_type};base64,{base64.b64encode(audio_bytes).decode('ascii')}"
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "tts", "synthesis", "completed", started,
                                                      __import__("time").time_ns() // 1_000_000, 0, str(item.value)[:400], "音频已生成", None, {"text": str(item.value)[:400], "audioDataUrl": audio_data_url, "mimeType": mime_type}))
                except Exception as exc:
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "tts", "synthesis", "failed", started,
                                                      __import__("time").time_ns() // 1_000_000, 0, str(item.value)[:400], "", str(exc)[:400]))
                self.events.extend(generated)
                return generated
            if item.kind == "vision":
                provider = self._vllm()
                if provider is None:
                    return generated
                sequence = self._event_sequence()
                started = __import__("time").time_ns() // 1_000_000
                try:
                    result = await asyncio.to_thread(provider.response, "请描述这张图片。", item.value)
                    text = str(result or "")
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "vision", "understanding", "completed", started,
                                                      __import__("time").time_ns() // 1_000_000, 0, "图片输入", text[:400], None))
                except Exception as exc:
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "vision", "understanding", "failed", started,
                                                      __import__("time").time_ns() // 1_000_000, 0, "图片输入", "", str(exc)[:400]))
                self.events.extend(generated)
            return generated
        provider = self._llm()
        if provider is None:
            return generated
        sequence = self._event_sequence()
        prompt = self.snapshot.config.get("systemPrompt") or self.snapshot.config.get("profileSystemPrompt") or ""
        role_prompt = self.snapshot.config.get("rolePrompt") or self.snapshot.config.get("profilePersonality") or ""
        if role_prompt:
            prompt = f"{prompt}\n\n{role_prompt}".strip()
        dialogue = ([{"role": "system", "content": prompt}] if prompt else []) + [{"role": "user", "content": item.value}]
        started = __import__("time").time_ns() // 1_000_000
        try:
            text = await asyncio.to_thread(lambda: "".join(provider.response(self.snapshot.session_id, dialogue)))
            generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "llm", "response", "completed", started,
                                              __import__("time").time_ns() // 1_000_000, 0, str(item.value)[:400], text[:400], None))
            if self.runtime_models.get("TTS"):
                tts_started = __import__("time").time_ns() // 1_000_000
                try:
                    mime_type, audio_bytes = await self._synthesize(text)
                    audio_data_url = f"data:{mime_type};base64,{base64.b64encode(audio_bytes).decode('ascii')}"
                    generated.append(PlaygroundEvent(self.snapshot.session_id, self._event_sequence(), "tts", "synthesis", "completed", tts_started,
                                                      __import__("time").time_ns() // 1_000_000, 0, text[:400], "音频已生成", None, {"text": text[:400], "audioDataUrl": audio_data_url, "mimeType": mime_type}))
                except Exception as exc:
                    generated.append(PlaygroundEvent(self.snapshot.session_id, self._event_sequence(), "tts", "synthesis", "failed", tts_started,
                                                      __import__("time").time_ns() // 1_000_000, 0, text[:400], "", str(exc)[:400]))
        except Exception as exc:
            generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "llm", "response", "failed", started,
                                              __import__("time").time_ns() // 1_000_000, 0, str(item.value)[:400], "", str(exc)[:400]))
        self.events.extend(generated)
        return generated

    def accept(self, payload: dict[str, Any]) -> PlaygroundEvent:
        item = PlaygroundInput.parse(payload)
        if item.session_id != self.snapshot.session_id:
            raise ValueError("playground session mismatch")
        if item.sequence != self._next_input_sequence:
            raise ValueError("playground input sequence mismatch")
        self._next_input_sequence += 1
        event = PlaygroundEvent.completed(
            self.snapshot.session_id,
            self._event_sequence(),
            item.kind,
            "input",
            item.value if item.kind == "text" else f"{item.kind} input",
            "accepted",
        )
        self.events.append(event)
        return event

    def events_after(self, sequence: int) -> list[PlaygroundEvent]:
        return [event for event in self.events if event.sequence > sequence]
