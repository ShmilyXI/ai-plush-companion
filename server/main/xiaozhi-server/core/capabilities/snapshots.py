from __future__ import annotations

from typing import Any, Mapping, Sequence


def build_device_tool_snapshot(
    functions: Sequence[Mapping[str, Any]],
    *,
    device_model: str | None = None,
    firmware_version: str | None = None,
) -> dict[str, Any]:
    tools = []
    for item in functions:
        function = item.get("function") if isinstance(item, Mapping) else None
        if not isinstance(function, Mapping):
            continue
        name = function.get("name")
        parameters = function.get("parameters")
        if not isinstance(name, str) or not name.strip() or not isinstance(parameters, Mapping):
            continue
        tools.append(
            {
                "name": name.strip(),
                "inputSchema": dict(parameters),
                "available": True,
            }
        )
    return {
        "deviceModel": device_model,
        "firmwareVersion": firmware_version,
        "tools": tools,
    }
