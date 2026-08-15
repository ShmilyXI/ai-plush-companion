from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Mapping
import re


class CapabilityModelError(ValueError):
    pass


TRIGGER_TYPES = {"KEYWORD", "REGEX", "POSITIVE_EXAMPLE", "NEGATIVE_EXAMPLE"}
TOOL_TYPES = {"PLUGIN", "MCP", "DEVICE_TOOL"}
RESPONSE_MODES = {"LLM", "FIXED"}


@dataclass(frozen=True)
class Trigger:
    type: str
    value: str
    priority: int = 0
    case_sensitive: bool = False
    enabled: bool = True
    regex: re.Pattern[str] | None = field(default=None, repr=False, compare=False)


@dataclass(frozen=True)
class Tool:
    name: str
    type: str
    ref_id: str
    alias: str | None = None
    purpose: str | None = None
    required: bool = False
    defaults: Mapping[str, Any] = field(default_factory=dict)
    runtime: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        object.__setattr__(self, "defaults", MappingProxyType(dict(self.defaults)))
        object.__setattr__(self, "runtime", MappingProxyType(dict(self.runtime)))


@dataclass(frozen=True)
class Skill:
    id: str
    version: int
    name: str
    description: str | None
    execution_prompt: str
    semantic_threshold: Decimal
    response_mode: str
    timeout_ms: int
    failure_message: str | None
    binding_priority: int
    triggers: tuple[Trigger, ...]
    tool_names: tuple[str, ...]
    defaults: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        object.__setattr__(self, "defaults", MappingProxyType(dict(self.defaults)))


@dataclass(frozen=True)
class CapabilityBundle:
    device_id: str
    config_version: int
    skills: tuple[Skill, ...]
    tools: Mapping[str, Tool]

    def __post_init__(self):
        object.__setattr__(self, "skills", tuple(self.skills))
        object.__setattr__(self, "tools", MappingProxyType(dict(self.tools)))

    @classmethod
    def parse(cls, value: Any) -> "CapabilityBundle":
        root = _object(value, {"deviceId", "configVersion", "skills", "tools"})
        device_id = _text(root, "deviceId")
        config_version = _integer(root, "configVersion", minimum=0)
        raw_tools = root.get("tools")
        if not isinstance(raw_tools, dict):
            raise CapabilityModelError("tools must be an object")
        tools: dict[str, Tool] = {}
        for key, raw in raw_tools.items():
            if not isinstance(key, str) or not key:
                raise CapabilityModelError("invalid tool key")
            tool = _parse_tool(raw)
            if tool.name != key:
                raise CapabilityModelError("tool key mismatch")
            tools[key] = tool
        raw_skills = root.get("skills")
        if not isinstance(raw_skills, list):
            raise CapabilityModelError("skills must be an array")
        skills = tuple(_parse_skill(raw) for raw in raw_skills)
        for skill in skills:
            if any(name not in tools for name in skill.tool_names):
                raise CapabilityModelError("skill references an absent tool")
        return cls(device_id=device_id, config_version=config_version, skills=skills, tools=tools)


def _parse_tool(value: Any) -> Tool:
    raw = _object(value, {"name", "type", "refId", "alias", "purpose", "required", "defaults", "runtime"})
    tool_type = _text(raw, "type").upper()
    if tool_type not in TOOL_TYPES:
        raise CapabilityModelError("unsupported tool type")
    defaults = raw.get("defaults", {})
    if not isinstance(defaults, dict):
        raise CapabilityModelError("tool defaults must be an object")
    runtime = raw.get("runtime", {})
    if not isinstance(runtime, dict):
        raise CapabilityModelError("tool runtime must be an object")
    return Tool(
        name=_text(raw, "name"),
        type=tool_type,
        ref_id=_text(raw, "refId"),
        alias=_optional_text(raw.get("alias")),
        purpose=_optional_text(raw.get("purpose")),
        required=_boolean(raw.get("required", False)),
        defaults=defaults,
        runtime=runtime,
    )


def _parse_skill(value: Any) -> Skill:
    raw = _object(
        value,
        {
            "id", "version", "name", "description", "executionPrompt", "semanticThreshold",
            "responseMode", "timeoutMs", "failureMessage", "bindingPriority", "triggers",
            "toolNames", "defaults",
        },
    )
    version = _integer(raw, "version", minimum=1)
    response_mode = _text(raw, "responseMode").upper()
    if response_mode not in RESPONSE_MODES:
        raise CapabilityModelError("unsupported response mode")
    threshold = _decimal(raw.get("semanticThreshold"))
    if threshold < 0 or threshold > 1:
        raise CapabilityModelError("invalid semantic threshold")
    raw_triggers = raw.get("triggers")
    if not isinstance(raw_triggers, list):
        raise CapabilityModelError("triggers must be an array")
    raw_names = raw.get("toolNames")
    if not isinstance(raw_names, list) or any(not isinstance(name, str) or not name for name in raw_names):
        raise CapabilityModelError("toolNames must be a string array")
    defaults = raw.get("defaults", {})
    if not isinstance(defaults, dict):
        raise CapabilityModelError("skill defaults must be an object")
    return Skill(
        id=_text(raw, "id"),
        version=version,
        name=_text(raw, "name"),
        description=_optional_text(raw.get("description")),
        execution_prompt=_text(raw, "executionPrompt"),
        semantic_threshold=threshold,
        response_mode=response_mode,
        timeout_ms=_integer(raw, "timeoutMs", minimum=1),
        failure_message=_optional_text(raw.get("failureMessage")),
        binding_priority=_integer(raw, "bindingPriority", minimum=None),
        triggers=tuple(_parse_trigger(item) for item in raw_triggers),
        tool_names=tuple(raw_names),
        defaults=defaults,
    )


def _parse_trigger(value: Any) -> Trigger:
    raw = _object(value, {"type", "value", "priority", "caseSensitive", "enabled"})
    trigger_type = _text(raw, "type").upper()
    if trigger_type not in TRIGGER_TYPES:
        raise CapabilityModelError("unsupported trigger type")
    pattern = _text(raw, "value")
    compiled = None
    if trigger_type == "REGEX":
        flags = 0 if _boolean(raw.get("caseSensitive", False)) else re.IGNORECASE
        try:
            compiled = re.compile(pattern, flags)
        except re.error as exc:
            raise CapabilityModelError("invalid regex trigger") from exc
    return Trigger(
        type=trigger_type,
        value=pattern,
        priority=_integer(raw, "priority", minimum=None),
        case_sensitive=_boolean(raw.get("caseSensitive", False)),
        enabled=_boolean(raw.get("enabled", True)),
        regex=compiled,
    )


def _object(value: Any, allowed: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CapabilityModelError("expected object")
    if set(value) - allowed:
        raise CapabilityModelError("object contains unsupported fields")
    return value


def _text(value: Mapping[str, Any], key: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result.strip():
        raise CapabilityModelError(f"missing {key}")
    return result.strip()


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise CapabilityModelError("expected optional string")
    return value


def _integer(value: Mapping[str, Any], key: str, minimum: int | None) -> int:
    result = value.get(key)
    if isinstance(result, bool) or not isinstance(result, int):
        raise CapabilityModelError(f"invalid {key}")
    if minimum is not None and result < minimum:
        raise CapabilityModelError(f"invalid {key}")
    return result


def _decimal(value: Any) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        raise CapabilityModelError("invalid decimal")
    try:
        return Decimal(str(value))
    except Exception as exc:
        raise CapabilityModelError("invalid decimal") from exc


def _boolean(value: Any) -> bool:
    if not isinstance(value, bool):
        raise CapabilityModelError("invalid boolean")
    return value
