from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..contract import ConversationEvent, ConversationInput


class PublicConversationAdapter:
    """Translate public JSON/binary frames to the shared runtime vocabulary."""

    def decode_input(self, payload: Mapping[str, Any] | bytes) -> ConversationInput:
        if isinstance(payload, bytes):
            return ConversationInput(kind="audio", request_id="audio-frame", audio=payload)
        kind = payload.get("type") or payload.get("kind")
        request_id = payload.get("request_id", payload.get("requestId"))
        if not isinstance(request_id, str) or not request_id.strip():
            raise ValueError("request_id is required")
        if kind in {"turn.text", "input.text", "text"}:
            return ConversationInput(kind="text", request_id=str(request_id), text=str(payload.get("text") or ""))
        if kind in {"input.audio.commit", "audio_commit"}:
            return ConversationInput(kind="audio_commit", request_id=str(request_id), duration_ms=payload.get("duration_ms"))
        raise ValueError("unsupported public conversation input")

    def encode_event(self, value: ConversationEvent) -> dict[str, Any]:
        return {
            "type": value.kind,
            "sequence": value.sequence,
            "conversation_id": value.conversation_id,
            "turn_id": value.turn_id,
            "details": dict(value.details),
        }
