from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .tools import MAX_TOOL_CALLS_PER_TURN


@dataclass(frozen=True)
class PublicToolCall:
    id: str
    name: str
    arguments: dict[str, Any]

    def assistant_value(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "type": "function",
            "function": {
                "name": self.name,
                "arguments": json.dumps(self.arguments, ensure_ascii=False, separators=(",", ":")),
            },
        }


class PublicToolCallError(ValueError):
    pass


class PublicToolCallAccumulator:
    def __init__(self):
        self._calls: dict[int, dict[str, str]] = {}

    def add(self, fragments) -> None:
        for fallback_index, fragment in enumerate(fragments or []):
            index = _value(fragment, "index")
            index = index if isinstance(index, int) and not isinstance(index, bool) else fallback_index
            if index < 0 or index >= MAX_TOOL_CALLS_PER_TURN:
                raise PublicToolCallError("too many tool calls")
            target = self._calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
            call_id = _value(fragment, "id")
            function = _value(fragment, "function")
            name = _value(function, "name")
            arguments = _value(function, "arguments")
            if isinstance(call_id, str) and call_id:
                target["id"] = call_id
            if isinstance(name, str) and name:
                target["name"] += name
            if isinstance(arguments, str) and arguments:
                target["arguments"] += arguments
        if len(self._calls) > MAX_TOOL_CALLS_PER_TURN:
            raise PublicToolCallError("too many tool calls")

    def finish(self) -> list[PublicToolCall]:
        result = []
        for index in sorted(self._calls):
            item = self._calls[index]
            if not item["id"] or not item["name"]:
                raise PublicToolCallError("tool call identity is missing")
            try:
                arguments = json.loads(item["arguments"] or "{}")
            except json.JSONDecodeError as error:
                raise PublicToolCallError("tool arguments are invalid JSON") from error
            if not isinstance(arguments, dict):
                raise PublicToolCallError("tool arguments must be an object")
            result.append(PublicToolCall(item["id"], item["name"], arguments))
        return result


def _value(value, name):
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)
