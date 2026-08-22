from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .protocol import PlaygroundEvent, PlaygroundInput, PlaygroundSnapshot
import asyncio
import tempfile
from pathlib import Path


@dataclass
class PlaygroundSession:
    snapshot: PlaygroundSnapshot
    runtime_models: dict[str, dict[str, Any]] = field(default_factory=dict)
    events: list[PlaygroundEvent] = field(default_factory=list)
    _next_sequence: int = 1

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

    async def execute(self, item: PlaygroundInput) -> list[PlaygroundEvent]:
        generated: list[PlaygroundEvent] = []
        if item.kind != "text":
            if item.kind == "vision":
                provider = self._vllm()
                if provider is None:
                    return generated
                sequence = self._next_sequence
                self._next_sequence += 1
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
        sequence = self._next_sequence
        self._next_sequence += 1
        prompt = self.snapshot.config.get("profileSystemPrompt") or self.snapshot.config.get("systemPrompt") or ""
        dialogue = ([{"role": "system", "content": prompt}] if prompt else []) + [{"role": "user", "content": item.value}]
        started = __import__("time").time_ns() // 1_000_000
        try:
            text = await asyncio.to_thread(lambda: "".join(provider.response(self.snapshot.session_id, dialogue)))
            generated.append(PlaygroundEvent(self.snapshot.session_id, sequence, "llm", "response", "completed", started,
                                              __import__("time").time_ns() // 1_000_000, 0, str(item.value)[:400], text[:400], None))
            tts_provider = self._tts()
            if tts_provider is not None:
                tts_started = __import__("time").time_ns() // 1_000_000
                output_path = str(Path(tempfile.gettempdir()) / f"playground-{self.snapshot.session_id}-{sequence}.wav")
                try:
                    await tts_provider.text_to_speak(text, output_path)
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence + 1, "tts", "synthesis", "completed", tts_started,
                                                      __import__("time").time_ns() // 1_000_000, 0, text[:400], "音频已生成", None))
                except Exception as exc:
                    generated.append(PlaygroundEvent(self.snapshot.session_id, sequence + 1, "tts", "synthesis", "failed", tts_started,
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
        if item.sequence != self._next_sequence:
            raise ValueError("playground input sequence mismatch")
        self._next_sequence += 1
        event = PlaygroundEvent.completed(
            self.snapshot.session_id,
            item.sequence,
            item.kind,
            "input",
            item.value if item.kind == "text" else f"{item.kind} input",
            "accepted",
        )
        self.events.append(event)
        return event

    def events_after(self, sequence: int) -> list[PlaygroundEvent]:
        return [event for event in self.events if event.sequence > sequence]
