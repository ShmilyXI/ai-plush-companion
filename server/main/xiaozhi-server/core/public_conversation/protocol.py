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

    def __post_init__(self) -> None:
        _require_text("request_id", self.request_id)
        if not isinstance(self.sequence, int) or self.sequence < 0:
            raise ValueError("sequence must be non-negative")
        _require_text("data", self.data)
        if len(self.data) > MAX_AUDIO_BASE64_LENGTH:
            raise ValueError("audio is too large")
        if not isinstance(self.final, bool):
            raise ValueError("final must be boolean")


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
