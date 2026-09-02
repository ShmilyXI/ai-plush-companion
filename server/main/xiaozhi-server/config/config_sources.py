"""Explicit, auditable configuration source resolution."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Mapping


SOURCE_ORDER = (
    "built_in_defaults",
    "local_override",
    "manager_api_server",
    "runtime_bundle",
    "request_override",
)

# A deployment can keep its locally reachable transport endpoint while Java
# supplies the rest of the server policy.
LOCAL_TRANSPORT_PATHS = frozenset(
    {
        "server.ip",
        "server.port",
        "server.http_port",
        "server.websocket",
        "server.vision_explain",
        "server.auth_key",
    }
)


@dataclass(frozen=True)
class ConfigSource:
    name: str
    priority: int
    values: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.name not in SOURCE_ORDER:
            raise ValueError(f"unsupported configuration source: {self.name}")
        object.__setattr__(self, "values", MappingProxyType(deepcopy(dict(self.values))))


def resolve_config(sources: list[ConfigSource] | tuple[ConfigSource, ...]):
    """Return an effective mapping and source labels for every leaf path."""

    by_name = {source.name: source for source in sources}
    unknown = set(by_name) - set(SOURCE_ORDER)
    if unknown:
        raise ValueError(f"unsupported configuration source: {sorted(unknown)}")

    effective: dict[str, Any] = {}
    source_map: dict[str, str] = {}
    ordered = sorted(
        by_name.values(),
        key=lambda source: (SOURCE_ORDER.index(source.name), source.priority),
    )
    for source in ordered:
        _merge(
            effective,
            source.values,
            source.name,
            source_map,
            path=(),
            local_transport_source=by_name.get("local_override"),
        )
    return effective, source_map


def _merge(
    destination: dict[str, Any],
    incoming: Mapping[str, Any],
    source_name: str,
    source_map: dict[str, str],
    *,
    path: tuple[str, ...],
    local_transport_source: ConfigSource | None,
) -> None:
    for key, value in incoming.items():
        current_path = path + (str(key),)
        dotted_path = ".".join(current_path)
        if isinstance(value, Mapping) and isinstance(destination.get(key), Mapping):
            nested = dict(destination[key])
            _merge(
                nested,
                value,
                source_name,
                source_map,
                path=current_path,
                local_transport_source=local_transport_source,
            )
            destination[key] = nested
            continue

        if (
            source_name == "manager_api_server"
            and dotted_path in LOCAL_TRANSPORT_PATHS
            and local_transport_source is not None
            and _contains_path(local_transport_source.values, current_path)
        ):
            continue

        destination[key] = deepcopy(value)
        source_map[dotted_path] = source_name


def _contains_path(value: Mapping[str, Any], path: tuple[str, ...]) -> bool:
    current: Any = value
    for key in path:
        if not isinstance(current, Mapping) or key not in current:
            return False
        current = current[key]
    return True
