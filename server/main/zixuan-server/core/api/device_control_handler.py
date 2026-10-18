import hmac
import json

from aiohttp import web

from core.providers.tools.device_mcp.mcp_handler import call_mcp_tool
from core.utils.util import sanitize_tool_name


class DeviceControlHandler:
    def __init__(self, config, connection_registry):
        self.config = config
        self.connection_registry = connection_registry

    async def handle_post(self, request):
        self._authenticate(request)
        body = await self._json_body(request)
        device_id = self._required_text(body, "mac_address")
        method = self._required_text(body, "method")
        params = body.get("params", {})
        if not isinstance(params, dict):
            raise web.HTTPBadRequest(text="invalid params")

        connection = self.connection_registry.get_connection(device_id)
        if connection is None:
            raise web.HTTPServiceUnavailable(text="device websocket is unavailable")
        mcp_client = getattr(connection, "mcp_client", None)
        if mcp_client is None or not await mcp_client.is_ready():
            raise web.HTTPServiceUnavailable(text="device MCP is unavailable")

        if method == "tools/list":
            async with mcp_client.lock:
                tools = list(mcp_client.tools.values())
            return web.json_response({"success": True, "data": {"tools": tools}})

        if method != "tools/call":
            raise web.HTTPBadRequest(text="unsupported method")
        tool_name = self._required_text(params, "name")
        arguments = params.get("arguments", {})
        if not isinstance(arguments, dict):
            raise web.HTTPBadRequest(text="invalid arguments")

        try:
            result = await call_mcp_tool(
                connection,
                mcp_client,
                sanitize_tool_name(tool_name),
                arguments,
                timeout=5,
            )
        except ValueError as exc:
            return web.json_response({"success": False, "error": str(exc)})
        except Exception as exc:
            return web.json_response({"success": False, "error": str(exc)})

        if isinstance(result, str):
            try:
                result = json.loads(result)
            except json.JSONDecodeError:
                pass
        return web.json_response({"success": True, "data": result})

    def _authenticate(self, request):
        token = request.headers.get("Authorization", "").removeprefix("Bearer ")
        expected = self.config.get("server", {}).get("auth_key") or self.config.get(
            "manager-api", {}
        ).get("secret", "")
        if not token or not expected or not hmac.compare_digest(token, expected):
            raise web.HTTPUnauthorized()

    @staticmethod
    async def _json_body(request):
        try:
            body = await request.json()
        except Exception as exc:
            raise web.HTTPBadRequest(text="invalid JSON body") from exc
        if not isinstance(body, dict):
            raise web.HTTPBadRequest(text="invalid JSON body")
        return body

    @staticmethod
    def _required_text(body, key):
        value = body.get(key)
        if not isinstance(value, str) or not value.strip():
            raise web.HTTPBadRequest(text=f"invalid {key}")
        return value.strip()
