from __future__ import annotations

from dataclasses import dataclass
from typing import AsyncIterator, Literal, Mapping, Protocol

from core.capabilities.models import CapabilityBundle


@dataclass(frozen=True)
class ConversationRequest:
    user_id: str | None
    profile_id: str
    conversation_id: str
    source: Literal["app", "device"]
    input_mode: Literal["text", "audio"]
    output_mode: Literal["text", "audio"]
    text: str | None = None
    audio: bytes | None = None
    audio_format: str | None = None
    capability_bundle: CapabilityBundle | None = None

    def __post_init__(self) -> None:
        if not self.profile_id or not self.conversation_id:
            raise ValueError("profile_id and conversation_id are required")
        if self.source not in {"app", "device"}:
            raise ValueError("source must be app or device")
        if self.input_mode not in {"text", "audio"}:
            raise ValueError("input_mode must be text or audio")
        if self.output_mode not in {"text", "audio"}:
            raise ValueError("output_mode must be text or audio")
        if self.audio is not None and not isinstance(self.audio, bytes):
            raise TypeError("audio must be bytes")


@dataclass(frozen=True)
class ConversationInput:
    kind: Literal["text", "audio", "audio_commit"]
    request_id: str
    text: str | None = None
    audio: bytes | None = None
    duration_ms: int | None = None

    def __post_init__(self) -> None:
        if self.kind not in {"text", "audio", "audio_commit"}:
            raise ValueError("unsupported conversation input kind")
        if not self.request_id:
            raise ValueError("request_id is required")
        if self.audio is not None and not isinstance(self.audio, bytes):
            raise TypeError("audio must be bytes")
        if self.duration_ms is not None and (
            isinstance(self.duration_ms, bool) or self.duration_ms < 0
        ):
            raise ValueError("duration_ms must be non-negative")


@dataclass(frozen=True)
class ConversationEvent:
    kind: str
    sequence: int
    conversation_id: str
    turn_id: str | None
    details: Mapping[str, object]


class ConversationHandle(Protocol):
    @property
    def events(self) -> AsyncIterator[ConversationEvent]: ...

    async def send(self, input: ConversationInput) -> None: ...
    async def cancel(self, reason: str) -> None: ...
    async def close(self) -> None: ...


class ConversationRuntimeProtocol(Protocol):
    async def start(self, request: ConversationRequest) -> ConversationHandle: ...
