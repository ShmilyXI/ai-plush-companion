from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from .contract import ConversationEvent


LIFECYCLE_KINDS = frozenset(
    {
        "session.started",
        "session.closed",
        "turn.started",
        "turn.completed",
        "turn.failed",
        "turn.cancelled",
    }
)


def event(
    kind: str,
    sequence: int,
    conversation_id: str,
    turn_id: str | None = None,
    details: Mapping[str, Any] | None = None,
) -> ConversationEvent:
    return ConversationEvent(
        kind=kind,
        sequence=sequence,
        conversation_id=conversation_id,
        turn_id=turn_id,
        details=dict(details or {}),
    )
