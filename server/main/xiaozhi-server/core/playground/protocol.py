from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Literal

InputKind = Literal["text", "audio", "vision", "activity"]
EventStatus = Literal["started", "completed", "failed"]


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _summary(value: Any, limit: int = 400) -> str:
    if value is None:
        return ""
    text = str(value)
    return text if len(text) <= limit else text[:limit]


@dataclass(frozen=True)
class VirtualDevice:
    width: int = 240
    height: int = 240
    depth: int = 8
    orientation: str = "square"
    screen: bool = True
    camera: bool = False
    microphone: bool = True
    activity_sensor: bool = False


@dataclass(frozen=True)
class PlaygroundSnapshot:
    session_id: str
    snapshot_version: int
    config: dict[str, Any]
    virtual_device: VirtualDevice


@dataclass(frozen=True)
class PlaygroundInput:
    session_id: str
    sequence: int
    kind: InputKind
    value: Any

    @classmethod
    def parse(cls, payload: dict[str, Any]) -> "PlaygroundInput":
        if not isinstance(payload, dict):
            raise ValueError("playground input must be an object")
        session_id = payload.get("session_id")
        sequence = payload.get("sequence")
        kind = payload.get("kind")
        if not isinstance(session_id, str) or not session_id:
            raise ValueError("session_id is required")
        if not isinstance(sequence, int) or sequence < 1:
            raise ValueError("sequence must be positive")
        if kind not in {"text", "audio", "vision", "activity"}:
            raise ValueError("unsupported playground input kind")
        fields = {"text": "text", "audio": "audio_ref", "vision": "image_ref", "activity": "activity"}
        key = fields[kind]
        value = payload.get(key)
        if value is None or value == "" or value == {}:
            raise ValueError(f"{key} is required")
        provided = [name for name in fields.values() if payload.get(name) not in (None, "", {})]
        if provided != [key]:
            raise ValueError("playground input must contain exactly one payload")
        return cls(session_id=session_id, sequence=sequence, kind=kind, value=value)


@dataclass(frozen=True)
class PlaygroundEvent:
    session_id: str
    sequence: int
    capability: str
    stage: str
    status: EventStatus
    started_at: int
    finished_at: int | None = None
    duration_ms: int | None = None
    input_summary: str = ""
    output_summary: str = ""
    error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["input_summary"] = _summary(data["input_summary"])
        data["output_summary"] = _summary(data["output_summary"])
        data["error"] = _summary(data["error"]) or None
        return data

    @classmethod
    def completed(cls, session_id: str, sequence: int, capability: str, stage: str,
                  input_summary: Any = "", output_summary: Any = "") -> "PlaygroundEvent":
        started = _now_ms()
        return cls(session_id, sequence, capability, stage, "completed", started, started, 0,
                   _summary(input_summary), _summary(output_summary))
