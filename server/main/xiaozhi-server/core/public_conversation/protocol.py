from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping
import re


_EVENT_TYPE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*$")
_SENSITIVE_KEYS = {
    "api_key",
    "access_token",
    "authorization",
    "password",
    "secret",
    "token",
}
MAX_TEXT_LENGTH = 8_000
MAX_AUDIO_BASE64_LENGTH = 2 * 1024 * 1024
MAX_AUDIO_DURATION_MS = 60_000
MAX_OUTPUT_TEXT_LENGTH = 12_000
MAX_AUDIO_OUTPUT_BYTES = 4 * 1024 * 1024
MAX_STREAM_FRAME_BYTES = 256 * 1024


def _require_text(name: str, value: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} is required")
    return value.strip()


def _redact(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            str(key): _redact(item)
            for key, item in value.items()
            if str(key).lower() not in _SENSITIVE_KEYS
        }
    if isinstance(value, list):
        return [_redact(item) for item in value]
    if isinstance(value, tuple):
        return [_redact(item) for item in value]
    return value


@dataclass(frozen=True)
class ConversationEvent:
    event_type: str
    conversation_id: str
    turn_id: str | None
    sequence: int
    occurred_at: int
    details: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        _require_text("event_type", self.event_type)
        if _EVENT_TYPE.fullmatch(self.event_type) is None:
            raise ValueError("event_type is invalid")
        _require_text("conversation_id", self.conversation_id)
        if self.turn_id is not None:
            _require_text("turn_id", self.turn_id)
        if not isinstance(self.sequence, int) or self.sequence <= 0:
            raise ValueError("sequence must be positive")
        if not isinstance(self.occurred_at, int) or self.occurred_at <= 0:
            raise ValueError("occurred_at must be positive")

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.event_type,
            "conversation_id": self.conversation_id,
            "turn_id": self.turn_id,
            "sequence": self.sequence,
            "occurred_at": self.occurred_at,
            "details": _redact(self.details),
        }


@dataclass(frozen=True)
class TextTurnInput:
    request_id: str
    text: str

    def __post_init__(self) -> None:
        _require_text("request_id", self.request_id)
        text = _require_text("text", self.text)
        if len(text) > MAX_TEXT_LENGTH:
            raise ValueError("text is too long")


@dataclass(frozen=True)
class AudioTurnInput:
    request_id: str
    sequence: int
    data: str
    final: bool
    duration_ms: int | None = None

    def __post_init__(self) -> None:
        _require_text("request_id", self.request_id)
        if not isinstance(self.sequence, int) or self.sequence < 0:
            raise ValueError("sequence must be non-negative")
        _require_text("data", self.data)
        if len(self.data) > MAX_AUDIO_BASE64_LENGTH:
            raise ValueError("audio is too large")
        if not isinstance(self.final, bool):
            raise ValueError("final must be boolean")
        if self.duration_ms is not None:
            if isinstance(self.duration_ms, bool) or not isinstance(self.duration_ms, int) or self.duration_ms <= 0:
                raise ValueError("audio duration must be positive")
            if self.duration_ms > MAX_AUDIO_DURATION_MS:
                raise ValueError("audio duration is too long")


@dataclass(frozen=True)
class StreamStartInput:
    request_id: str
    sample_rate: int
    channels: int
    format: str

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "StreamStartInput":
        if not isinstance(payload, Mapping) or payload.get("type") != "stream.start":
            raise ValueError("stream.start is required")
        request_id = _require_text("request_id", payload.get("request_id"))
        audio = payload.get("audio")
        if not isinstance(audio, Mapping):
            raise ValueError("audio is required")
        if audio.get("format") != "pcm_s16le":
            raise ValueError("audio format must be pcm_s16le")
        if audio.get("sample_rate") != 16000:
            raise ValueError("sample_rate must be 16000")
        if audio.get("channels") != 1:
            raise ValueError("channels must be 1")
        return cls(request_id, 16000, 1, "pcm_s16le")


@dataclass(frozen=True)
class StreamControlFrame:
    type: str
    turn_id: str | None = None
    request_id: str | None = None

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "StreamControlFrame":
        if not isinstance(payload, Mapping):
            raise ValueError("stream frame must be an object")
        frame_type = payload.get("type")
        if frame_type not in {"stream.audio.end", "stream.stop"}:
            if frame_type == "turn.cancel":
                return cls(frame_type, _require_text("turn_id", payload.get("turn_id")))
            raise ValueError("unsupported stream frame")
        request_id = payload.get("request_id")
        if request_id is not None:
            request_id = _require_text("request_id", request_id)
        return cls(frame_type, None, request_id)


@dataclass(frozen=True)
class RuntimeTokenClaims:
    conversation_id: str
    subject: str
    agent_id: str
    agent_version: int
    scopes: tuple[str, ...]
    input_modes: tuple[str, ...]
    output_modes: tuple[str, ...]
    issued_at: int
    expires_at: int
