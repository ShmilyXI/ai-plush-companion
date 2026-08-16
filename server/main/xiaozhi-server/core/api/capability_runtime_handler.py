from __future__ import annotations

import hmac
import json
import re
from collections.abc import Mapping
from typing import Any, Callable
from urllib.parse import parse_qsl, urlsplit

from aiohttp import web

from core.providers.tools.server_mcp.config_resolver import (
    BackendMCPConfigResolver,
    MCPConfigurationError,
)


class CapabilityRuntimeHandler:
    def __init__(
        self,
        config: Mapping[str, Any],
        *,
        plugin_registry: Mapping[str, Any] | None = None,
        mcp_client_factory: Callable[[dict[str, Any]], Any] | None = None,
    ):
        self.config = config
        if plugin_registry is None:
            from plugins_func.register import all_function_registry

            plugin_registry = all_function_registry
        self.plugin_registry = plugin_registry
        self._mcp_client_factory = mcp_client_factory or self._create_mcp_client

    async def handle_plugin_executors(self, request):
        self._authenticate(request)
        executors = []
        for registered_name, item in sorted(self.plugin_registry.items()):
            metadata = getattr(item, "description", None)
            function = metadata.get("function") if isinstance(metadata, Mapping) else None
            if not isinstance(function, Mapping):
                continue
            name = function.get("name")
            description = function.get("description")
            input_schema = function.get("parameters")
            if not isinstance(name, str) or not name.strip():
                name = registered_name
            if not isinstance(name, str) or not name.strip():
                continue
            if not isinstance(description, str):
                description = ""
            if not isinstance(input_schema, Mapping):
                input_schema = {"type": "object", "properties": {}}
            executors.append(
                {
                    "name": name.strip(),
                    "description": description,
                    "inputSchema": self._json_copy(input_schema),
                }
            )
        return web.json_response({"executors": executors})

    async def handle_mcp_test(self, request):
        self._authenticate(request)
        client = None
        try:
            body = await self._json_body(request)
            config = self._connection_config(body)
            client = self._mcp_client_factory(config)
            await client.initialize()
            tools = self._sanitize_tools(client.get_discovered_tool_snapshots())
            return web.json_response({"success": True, "tools": tools})
        except MCPConfigurationError:
            return web.json_response(
                {"success": False, "errorClass": "MCPConfigurationError"},
                status=400,
            )
        except web.HTTPException:
            raise
        except Exception as exc:
            return web.json_response(
                {"success": False, "errorClass": type(exc).__name__},
                status=502,
            )
        finally:
            if client is not None:
                try:
                    await client.cleanup()
                except Exception:
                    pass

    def _authenticate(self, request):
        token = request.headers.get("Authorization", "").removeprefix("Bearer ")
        expected = self.config.get("server", {}).get("auth_key") or self.config.get(
            "manager-api", {}
        ).get("secret", "")
        if not token or not expected or not hmac.compare_digest(token, expected):
            raise web.HTTPUnauthorized()

    async def _json_body(self, request):
        try:
            body = await request.json()
        except Exception as exc:
            raise web.HTTPBadRequest(text="invalid JSON body") from exc
        if not isinstance(body, Mapping):
            raise web.HTTPBadRequest(text="invalid JSON body")
        return body

    def _connection_config(self, body: Mapping[str, Any]) -> dict[str, Any]:
        raw_transport = body.get("transport")
        connection = body.get("connectionConfig")
        if not isinstance(raw_transport, str) or not isinstance(connection, Mapping):
            raise MCPConfigurationError("invalid MCP connection configuration")
        transport = raw_transport.strip().upper().replace("-", "_")
        config = self._json_copy(connection)
        if transport == "STDIO":
            if "url" in config or not isinstance(config.get("command"), str):
                raise MCPConfigurationError("invalid stdio MCP configuration")
            approved = self._approved_stdio_template(config)
            supplied = body.get("approvedCommandTemplate")
            if approved is None or supplied != approved:
                raise MCPConfigurationError("stdio must use a server-approved template")
            BackendMCPConfigResolver._validate_stdio(config, approved)
            return config
        if transport == "SSE":
            self._validate_network_config(config)
            config["transport"] = "sse"
            return config
        if transport in {"STREAMABLE_HTTP", "HTTP"}:
            self._validate_network_config(config)
            config["transport"] = "streamable-http"
            return config
        raise MCPConfigurationError("unsupported MCP transport")

    @staticmethod
    def _validate_network_config(config):
        url = config.get("url")
        try:
            parsed = urlsplit(url) if isinstance(url, str) else None
            query_keys = {
                re.sub(r"[^a-z0-9]", "", key.lower())
                for key, _ in parse_qsl(parsed.query, keep_blank_values=True)
            } if parsed is not None else set()
        except ValueError:
            parsed = None
            query_keys = set()
        sensitive = (
            "authorization", "password", "secret", "credential", "token",
            "apikey", "privatekey", "accesskey",
        )
        if (
            "command" in config
            or "args" in config
            or not isinstance(url, str)
            or re.fullmatch(r"https?://[^\s]+", url) is None
            or parsed is None
            or parsed.username is not None
            or parsed.password is not None
            or any(any(part in key for part in sensitive) for key in query_keys)
        ):
            raise MCPConfigurationError("invalid network MCP configuration")

    @staticmethod
    def _approved_stdio_template(config: Mapping[str, Any]):
        command = config.get("command")
        args = config.get("args", [])
        if not isinstance(command, str) or not isinstance(args, list) or not all(
            isinstance(value, str) for value in args
        ):
            return None
        safe_url = r"^https?://[^\s]+$"
        safe_path = r"^/(?!\.\.(?:/|$))(?!.*?/\.\.(?:/|$))[^\r\n]*$"
        if command == "mcp-proxy" and len(args) == 1 and re.fullmatch(
            safe_url, args[0]
        ):
            return {
                "command": command,
                "argsPrefix": [],
                "extraArgPatterns": [safe_url],
            }
        playwright = ["-y", "@executeautomation/playwright-mcp-server"]
        if command == "npx" and args == playwright:
            return {
                "command": command,
                "argsPrefix": playwright,
                "extraArgPatterns": [],
            }
        filesystem = ["-y", "@modelcontextprotocol/server-filesystem"]
        extras = args[len(filesystem) :]
        if (
            command == "npx"
            and len(args) > len(filesystem)
            and args[: len(filesystem)] == filesystem
            and all(re.fullmatch(safe_path, value) for value in extras)
        ):
            return {
                "command": command,
                "argsPrefix": filesystem,
                "extraArgPatterns": [safe_path] * len(extras),
            }
        return None

    @classmethod
    def _sanitize_tools(cls, values):
        if not isinstance(values, list):
            raise MCPConfigurationError("invalid MCP tool response")
        tools = []
        for item in values:
            if not isinstance(item, Mapping):
                continue
            name = item.get("name")
            input_schema = item.get("inputSchema")
            if not isinstance(name, str) or not name.strip():
                continue
            if not isinstance(input_schema, Mapping):
                continue
            tools.append(
                {
                    "name": name.strip(),
                    "inputSchema": cls._json_copy(input_schema),
                }
            )
        return tools

    @staticmethod
    def _json_copy(value):
        try:
            return json.loads(json.dumps(value, ensure_ascii=False))
        except (TypeError, ValueError) as exc:
            raise MCPConfigurationError("configuration is not JSON serializable") from exc

    @staticmethod
    def _create_mcp_client(config):
        from core.providers.tools.server_mcp.mcp_client import ServerMCPClient

        return ServerMCPClient(config)
