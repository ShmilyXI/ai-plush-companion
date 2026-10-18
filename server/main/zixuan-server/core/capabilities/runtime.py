from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from .models import CapabilityBundle, Skill, Tool
from .router import SkillRouter


REQUIRED_SYSTEM_TOOLS = frozenset({"handle_exit_intent"})


@dataclass(frozen=True)
class SkillTurn:
    bundle: CapabilityBundle | None
    skill: Skill | None
    trigger_mode: str
    allowed_tool_names: frozenset[str]


@dataclass(frozen=True)
class PreparedToolCall:
    arguments: dict[str, Any]
    config: dict[str, Any]


class SkillTurnRuntime:
    def __init__(self):
        self._role_metadata: Mapping[str, Any] = {}

    def set_role_metadata(self, value: Mapping[str, Any] | None) -> None:
        self._role_metadata = dict(value or {})

    async def select(
        self,
        bundle: CapabilityBundle | None,
        utterance: str,
        classifier,
        *,
        tools_enabled: bool = True,
    ) -> SkillTurn:
        if not tools_enabled:
            return SkillTurn(bundle, None, "DISABLED", frozenset())
        if bundle is None or not bundle.skills:
            return SkillTurn(bundle, None, "NONE", REQUIRED_SYSTEM_TOOLS)

        decision = SkillRouter(bundle.skills).route(utterance or "")
        selected = decision.selected
        mode = decision.mode
        if selected is None and decision.semantic_required and classifier is not None:
            selected = await classifier.classify(utterance or "", decision.candidates)
            mode = "SEMANTIC" if selected is not None else "NONE"
        elif selected is None:
            mode = "NONE"

        allowed = set(REQUIRED_SYSTEM_TOOLS)
        if selected is not None:
            selected_tools = [bundle.tools.get(name) for name in selected.tool_names]
            if any(
                tool is not None and tool.required and not self._role_tool_matches(tool)
                for tool in selected_tools
            ):
                return SkillTurn(bundle, None, "ROLE_MISMATCH", frozenset(allowed))
            allowed.update(
                name for name, tool in zip(selected.tool_names, selected_tools)
                if tool is not None and self._role_tool_matches(tool)
            )
        return SkillTurn(bundle, selected, mode, frozenset(allowed))

    def _role_tool_matches(self, tool: Tool) -> bool:
        if tool.type != "ROLE_MCP":
            return True
        connection_agent_id = self._role_metadata.get("agentId")
        return (
            isinstance(connection_agent_id, str)
            and connection_agent_id
            and tool.runtime.get("agentId") == connection_agent_id
        )

    def inject_execution_prompt(
        self, messages: Sequence[Mapping[str, Any]], skill: Skill | None
    ) -> list[dict[str, Any]]:
        copied = [dict(message) for message in messages]
        if skill is None or not skill.execution_prompt.strip():
            return copied
        instruction = (
            "\n\n<skill_execution>\n"
            + skill.execution_prompt.strip()
            + "\n</skill_execution>"
        )
        for message in copied:
            if message.get("role") == "system":
                message["content"] = str(message.get("content") or "") + instruction
                break
        else:
            copied.insert(0, {"role": "system", "content": instruction.strip()})
        return copied

    def prepare_tool_call(
        self,
        tool: Tool,
        arguments: Mapping[str, Any] | None,
        function_description: Mapping[str, Any] | None,
        utterance: str | None = None,
        skill_defaults: Mapping[str, Any] | None = None,
    ) -> PreparedToolCall:
        provided = dict(arguments or {})
        properties = self._parameter_properties(function_description)
        defaults = dict(tool.defaults)
        defaults.update(skill_defaults or {})
        resolved_arguments = {
            key: value
            for key, value in defaults.items()
            if key in properties and value not in (None, "") and not key.endswith("_secret_id")
        }
        for key, value in provided.items():
            schema = properties.get(key)
            allowed_values = schema.get("enum") if isinstance(schema, Mapping) else None
            if isinstance(allowed_values, list) and allowed_values and value not in allowed_values:
                continue
            configured_default = resolved_arguments.get(key)
            if (
                isinstance(allowed_values, list)
                and configured_default not in (None, "")
                and value != configured_default
                and isinstance(utterance, str)
                and str(value).casefold() not in utterance.casefold()
            ):
                continue
            resolved_arguments[key] = value
        config = {
            key: value
            for key, value in defaults.items()
            if (key not in properties or key.endswith("_secret_id"))
            and value not in (None, "")
        }
        return PreparedToolCall(resolved_arguments, config)

    @staticmethod
    def _parameter_properties(
        description: Mapping[str, Any] | None,
    ) -> Mapping[str, Any]:
        if not isinstance(description, Mapping):
            return {}
        function = description.get("function")
        if not isinstance(function, Mapping):
            return {}
        parameters = function.get("parameters")
        if not isinstance(parameters, Mapping):
            return {}
        properties = parameters.get("properties")
        return properties if isinstance(properties, Mapping) else {}
