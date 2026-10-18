from __future__ import annotations

from dataclasses import dataclass
import hashlib
import inspect
import json
import re
from typing import Any, Awaitable, Callable, Mapping

from core.capabilities.models import CapabilityBundle


class MCPConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class ResolvedMCPServer:
    server_id: str
    transport: str
    config: dict[str, Any]
    allowed_tools: Mapping[str, Mapping[str, Any]]


class BackendMCPConfigResolver:
    def __init__(self, secret_loader: Callable[[str, str], Awaitable[str | None] | str | None]):
        self._secret_loader = secret_loader

    async def resolve(self, bundle: CapabilityBundle | None, *, local_loader=None):
        if bundle is None:
            return tuple()
        grouped: dict[str, dict[str, Any]] = {}
        for name, tool in bundle.tools.items():
            if tool.type != "MCP":
                continue
            if not tool.runtime:
                continue
            runtime = self._runtime(tool.runtime)
            server_id = runtime["serverId"]
            entry = grouped.setdefault(
                server_id,
                {
                    "transport": runtime["transport"],
                    "connectionConfig": runtime["connectionConfig"],
                    "secretRefs": runtime["secretRefs"],
                    "approvedCommandTemplate": runtime["approvedCommandTemplate"],
                    "allowedTools": {},
                },
            )
            self._require_consistent(entry, runtime)
            entry["allowedTools"][name] = {
                "inputSchema": runtime["inputSchema"],
                "schemaSha256": runtime["schemaSha256"],
            }

        resolved = []
        for server_id, value in grouped.items():
            config = json.loads(json.dumps(value["connectionConfig"]))
            transport = value["transport"]
            if transport == "STDIO":
                self._validate_stdio(config, value["approvedCommandTemplate"])
            for path, secret_id in value["secretRefs"].items():
                secret = self._secret_loader(bundle.device_id, secret_id)
                if inspect.isawaitable(secret):
                    secret = await secret
                if secret is None:
                    raise MCPConfigurationError("MCP secret is not configured")
                self._set_path(config, path, secret)
            if transport != "STDIO":
                config["transport"] = (
                    "streamable-http" if transport == "STREAMABLE_HTTP" else "sse"
                )
            resolved.append(
                ResolvedMCPServer(
                    server_id=server_id,
                    transport=transport,
                    config=config,
                    allowed_tools=dict(value["allowedTools"]),
                )
            )
        return tuple(sorted(resolved, key=lambda item: item.server_id))

    def _runtime(self, value: Mapping[str, Any]) -> dict[str, Any]:
        required = {
            "serverId", "transport", "connectionConfig", "secretRefs",
            "approvedCommandTemplate", "inputSchema", "schemaSha256",
        }
        if not isinstance(value, Mapping) or set(value) != required:
            raise MCPConfigurationError("invalid MCP runtime configuration")
        transport = value["transport"]
        if transport not in {"STDIO", "SSE", "STREAMABLE_HTTP"}:
            raise MCPConfigurationError("unsupported MCP transport")
        if not isinstance(value["serverId"], str) or not value["serverId"].strip():
            raise MCPConfigurationError("invalid MCP server id")
        if not isinstance(value["connectionConfig"], Mapping):
            raise MCPConfigurationError("invalid MCP connection config")
        if not isinstance(value["secretRefs"], Mapping):
            raise MCPConfigurationError("invalid MCP secret refs")
        if value["approvedCommandTemplate"] is not None and not isinstance(
            value["approvedCommandTemplate"], Mapping
        ):
            raise MCPConfigurationError("invalid approved command template")
        if not isinstance(value["inputSchema"], Mapping):
            raise MCPConfigurationError("invalid MCP input schema")
        if not isinstance(value["schemaSha256"], str) or not re.fullmatch(
            r"[0-9a-f]{64}", value["schemaSha256"]
        ):
            raise MCPConfigurationError("invalid MCP schema hash")
        return {
            "serverId": value["serverId"].strip(),
            "transport": transport,
            "connectionConfig": dict(value["connectionConfig"]),
            "secretRefs": {str(k): str(v) for k, v in value["secretRefs"].items()},
            "approvedCommandTemplate": (
                None if value["approvedCommandTemplate"] is None
                else dict(value["approvedCommandTemplate"])
            ),
            "inputSchema": dict(value["inputSchema"]),
            "schemaSha256": value["schemaSha256"],
        }

    @staticmethod
    def _require_consistent(entry, runtime):
        for key in (
            "transport", "connectionConfig", "secretRefs", "approvedCommandTemplate"
        ):
            if entry[key] != runtime[key]:
                raise MCPConfigurationError("inconsistent MCP server configuration")

    @staticmethod
    def _set_path(target: dict[str, Any], path: str, value: Any):
        parts = [part for part in path.split(".") if part]
        if not parts:
            raise MCPConfigurationError("invalid MCP secret path")
        current = target
        for part in parts[:-1]:
            child = current.get(part)
            if child is None:
                child = {}
                current[part] = child
            if not isinstance(child, dict):
                raise MCPConfigurationError("invalid MCP secret path")
            current = child
        current[parts[-1]] = value

    @staticmethod
    def _validate_stdio(config, template):
        if not isinstance(template, Mapping):
            raise MCPConfigurationError("stdio must match approved command template")
        command = config.get("command")
        args = config.get("args", [])
        prefix = template.get("argsPrefix", [])
        patterns = template.get("extraArgPatterns", [])
        if command != template.get("command") or not isinstance(args, list):
            raise MCPConfigurationError("stdio must match approved command template")
        if not isinstance(prefix, list) or args[: len(prefix)] != prefix:
            raise MCPConfigurationError("stdio must match approved command template")
        extras = args[len(prefix):]
        if not isinstance(patterns, list) or len(extras) != len(patterns):
            raise MCPConfigurationError("stdio must match approved command template")
        for value, pattern in zip(extras, patterns):
            if not isinstance(value, str) or not isinstance(pattern, str) or re.fullmatch(pattern, value) is None:
                raise MCPConfigurationError("stdio must match approved command template")


def filter_discovered_tools(server, discovered):
    accepted = []
    for item in discovered:
        if not isinstance(item, Mapping):
            continue
        name = item.get("name")
        schema = item.get("inputSchema")
        expected = server.allowed_tools.get(name)
        if expected is None or not isinstance(schema, Mapping):
            continue
        digest = _schema_hash(schema)
        if digest != expected.get("schemaSha256"):
            continue
        accepted.append({"name": name, "inputSchema": dict(schema)})
    return accepted


def _schema_hash(schema):
    encoded = json.dumps(
        schema, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
