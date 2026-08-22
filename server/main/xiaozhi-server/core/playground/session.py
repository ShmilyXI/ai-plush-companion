from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .protocol import PlaygroundEvent, PlaygroundInput, PlaygroundSnapshot


@dataclass
class PlaygroundSession:
    snapshot: PlaygroundSnapshot
    events: list[PlaygroundEvent] = field(default_factory=list)
    _next_sequence: int = 1

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
