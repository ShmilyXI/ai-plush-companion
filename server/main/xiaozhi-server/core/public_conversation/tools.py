from __future__ import annotations

import asyncio
import inspect
import time
from dataclasses import dataclass
from types import SimpleNamespace
from typing import Any, Mapping

from core.capabilities.models import CapabilityBundle
from core.capabilities.runtime import SkillTurn, SkillTurnRuntime
from plugins_func.register import Action, ActionResponse, ToolType


PUBLIC_READONLY_TOOL_REFS = {
    "get_weather": "plugin-weather",
    "get_news_from_newsnow": "plugin-news",
}
PUBLIC_READONLY_TOOLS = frozenset(PUBLIC_READONLY_TOOL_REFS)
MAX_TOOL_CALLS_PER_TURN = 3
MAX_TOOL_RESULT_LENGTH = 32_000


@dataclass(frozen=True)
class PublicToolResult:
    name: str
    content: str
    duration_ms: int


class PublicToolError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class PublicConversationToolRuntime:
    def __init__(self, bundle: Mapping[str, Any], *, plugin_registry=None):
        config = bundle.get("config") or {}
        raw_agent_version = bundle.get("agent_version") or bundle.get("agentVersion")
        agent_version = int(raw_agent_version) if isinstance(raw_agent_version, str) and raw_agent_version.isdigit() else raw_agent_version
        self.bundle = CapabilityBundle.parse({
            "deviceId": str(bundle.get("conversation_id") or bundle.get("conversationId") or "public"),
            "configVersion": 0,
            "agentId": bundle.get("agent_id") or bundle.get("agentId"),
            "agentVersionNo": agent_version,
            "skills": config.get("skills") or [],
            "tools": config.get("tools") or {},
        })
        if plugin_registry is None:
            try:
                from plugins_func.loadplugins import load_plugin_registry
            except ImportError:
                load_plugin_registry = None
            if callable(load_plugin_registry):
                plugin_registry = load_plugin_registry(
                    enabled_names=PUBLIC_READONLY_TOOLS,
                    required_names=PUBLIC_READONLY_TOOLS,
                )
            else:
                # Keep old test/integration harnesses usable while the
                # production path uses explicit manifests above.
                from plugins_func.register import all_function_registry
                plugin_registry = all_function_registry
        self._plugins = plugin_registry
        self._selector = SkillTurnRuntime()
        self._context = SimpleNamespace(
            config={"plugins": {}},
            client_ip=None,
            device_id=self.bundle.device_id,
            last_newsnow_link=None,
        )

    async def select(self, utterance: str) -> SkillTurn:
        return await self._selector.select(self.bundle, utterance, None, tools_enabled=True)

    def schemas(self, turn: SkillTurn) -> list[dict[str, Any]]:
        result = []
        for name in sorted(turn.allowed_tool_names):
            if not self._allowed(turn, name):
                continue
            schema = self.bundle.tools[name].runtime.get("schema")
            if isinstance(schema, Mapping):
                result.append(dict(schema))
        return result

    async def execute(
        self,
        turn: SkillTurn,
        name: str,
        arguments: Mapping[str, Any],
        *,
        timeout_seconds: float | None = None,
    ) -> PublicToolResult:
        if not self._allowed(turn, name):
            raise PublicToolError("not_allowed", f"tool {name} is not allowed")
        tool = self.bundle.tools[name]
        item = self._plugins.get(name)
        if item is None or item.type != ToolType.SYSTEM_CTL:
            raise PublicToolError("not_allowed", f"tool {name} is not allowed")
        schema = tool.runtime.get("schema")
        parameters = schema.get("function", {}).get("parameters", {}) if isinstance(schema, Mapping) else {}
        prepared = dict(tool.defaults)
        prepared.update(dict(arguments or {}))
        self._validate_arguments(parameters, prepared)
        timeout = timeout_seconds
        if timeout is None:
            timeout = max(0.001, min(turn.skill.timeout_ms / 1000 if turn.skill else 30.0, 30.0))
        started = time.monotonic()
        try:
            value = item.func(self._context, **prepared)
            if inspect.isawaitable(value):
                value = await asyncio.wait_for(value, timeout=timeout)
        except asyncio.TimeoutError as error:
            raise PublicToolError("timeout", "tool execution timed out") from error
        except TypeError as error:
            raise PublicToolError("invalid_arguments", "tool arguments are invalid") from error
        except Exception as error:
            raise PublicToolError("execution_failed", "tool execution failed") from error
        if not isinstance(value, ActionResponse) or value.action in {Action.ERROR, Action.NOTFOUND}:
            raise PublicToolError("execution_failed", "tool execution failed")
        content = value.result if value.result is not None else value.response
        content = str(content or "").strip()
        if not content:
            raise PublicToolError("execution_failed", "tool returned no result")
        if len(content) > MAX_TOOL_RESULT_LENGTH:
            raise PublicToolError("result_too_large", "tool result is too large")
        return PublicToolResult(
            name=name,
            content=content,
            duration_ms=max(0, int((time.monotonic() - started) * 1000)),
        )

    def _allowed(self, turn: SkillTurn, name: str) -> bool:
        tool = self.bundle.tools.get(name)
        return bool(
            turn is not None
            and turn.skill is not None
            and name in turn.allowed_tool_names
            and name in PUBLIC_READONLY_TOOLS
            and tool is not None
            and tool.type == "PLUGIN"
            and tool.ref_id == PUBLIC_READONLY_TOOL_REFS[name]
            and tool.runtime.get("executor") == "SERVER_PLUGIN"
        )

    @staticmethod
    def _validate_arguments(schema: Any, arguments: Mapping[str, Any]) -> None:
        if not isinstance(schema, Mapping) or schema.get("type") != "object":
            raise PublicToolError("invalid_arguments", "tool schema is invalid")
        properties = schema.get("properties") or {}
        required = schema.get("required") or []
        if not isinstance(properties, Mapping) or not isinstance(required, list):
            raise PublicToolError("invalid_arguments", "tool schema is invalid")
        if any(name not in arguments or arguments[name] in (None, "") for name in required):
            raise PublicToolError("invalid_arguments", "required tool argument is missing")
        if any(name not in properties for name in arguments):
            raise PublicToolError("invalid_arguments", "unknown tool argument")
        expected_types = {
            "string": lambda value: isinstance(value, str),
            "boolean": lambda value: isinstance(value, bool),
            "integer": lambda value: isinstance(value, int) and not isinstance(value, bool),
            "number": lambda value: isinstance(value, (int, float)) and not isinstance(value, bool),
        }
        for name, value in arguments.items():
            descriptor = properties.get(name)
            if not isinstance(descriptor, Mapping):
                raise PublicToolError("invalid_arguments", "tool argument schema is invalid")
            validator = expected_types.get(descriptor.get("type"))
            if validator is None or not validator(value):
                raise PublicToolError("invalid_arguments", f"tool argument {name} has invalid type")
            choices = descriptor.get("enum")
            if isinstance(choices, list) and value not in choices:
                raise PublicToolError("invalid_arguments", f"tool argument {name} is invalid")
