from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..contract import ConversationEvent, ConversationInput


class DeviceConversationAdapter:
    """Keep device authentication/framing separate from runtime orchestration."""

    def authenticate(self, headers: Mapping[str, str], expected_token: str) -> bool:
        token = headers.get("Authorization", "").removeprefix("Bearer ")
        return bool(token and expected_token and token == expected_token)

    def decode_audio(self, payload: bytes, request_id: str, duration_ms: int | None = None) -> ConversationInput:
        return ConversationInput(kind="audio", request_id=request_id, audio=payload, duration_ms=duration_ms)

    def encode_event(self, value: ConversationEvent) -> dict[str, Any]:
        return {
            "type": value.kind,
            "sequence": value.sequence,
            "conversation_id": value.conversation_id,
            "turn_id": value.turn_id,
            "details": dict(value.details),
        }
