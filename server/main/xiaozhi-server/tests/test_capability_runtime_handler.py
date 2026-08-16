import importlib
import json

import pytest


def runtime_handler_type():
    try:
        module = importlib.import_module("core.api.capability_runtime_handler")
    except ModuleNotFoundError:
        return None
    return module.CapabilityRuntimeHandler


class Request:
    def __init__(self, body=None, token="server-secret"):
        self._body = body
        self.headers = {"Authorization": f"Bearer {token}"}

    async def json(self):
        return self._body


def response_json(response):
    return json.loads(response.text)


@pytest.mark.asyncio
async def test_plugin_executor_catalog_exposes_only_registered_schema_metadata():
    handler_type = runtime_handler_type()
    assert handler_type is not None, "Capability runtime HTTP handler is missing"

    registry = {
        "get_weather": type(
            "FunctionItem",
            (),
            {
                "description": {
                    "type": "function",
                    "function": {
                        "name": "get_weather",
                        "description": "查询天气",
                        "parameters": {"type": "object", "properties": {}},
                    },
                }
            },
        )(),
    }
    handler = handler_type(
        {"manager-api": {"secret": "server-secret"}},
        plugin_registry=registry,
        mcp_client_factory=lambda *_: None,
    )

    response = await handler.handle_plugin_executors(Request())

    assert response_json(response) == {
        "executors": [
            {
                "name": "get_weather",
                "description": "查询天气",
                "inputSchema": {"type": "object", "properties": {}},
            }
        ]
    }


@pytest.mark.asyncio
async def test_mcp_connection_test_returns_sanitized_tools_and_always_cleans_up():
    handler_type = runtime_handler_type()
    assert handler_type is not None, "Capability runtime HTTP handler is missing"

    clients = []

    class Client:
        def __init__(self, config):
            self.config = config
            self.cleaned = False
            clients.append(self)

        async def initialize(self):
            return None

        def get_discovered_tool_snapshots(self):
            return [
                {
                    "name": "mcp_search",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                    },
                }
            ]

        async def cleanup(self):
            self.cleaned = True

    handler = handler_type(
        {"manager-api": {"secret": "server-secret"}},
        plugin_registry={},
        mcp_client_factory=Client,
    )
    request = Request(
        {
            "transport": "SSE",
            "connectionConfig": {
                "url": "https://mcp.example.test/sse",
                "headers": {"Authorization": "Bearer runtime-secret"},
            },
            "approvedCommandTemplate": None,
        }
    )

    response = await handler.handle_mcp_test(request)
    body = response_json(response)

    assert body["success"] is True
    assert body["tools"] == [
        {
            "name": "mcp_search",
            "inputSchema": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
            },
        }
    ]
    assert "runtime-secret" not in response.text
    assert clients[0].config["transport"] == "sse"
    assert clients[0].cleaned is True


@pytest.mark.asyncio
async def test_mcp_connection_test_rejects_unapproved_stdio_before_starting_process():
    handler_type = runtime_handler_type()
    assert handler_type is not None, "Capability runtime HTTP handler is missing"

    started = False

    def client_factory(_):
        nonlocal started
        started = True

    handler = handler_type(
        {"manager-api": {"secret": "server-secret"}},
        plugin_registry={},
        mcp_client_factory=client_factory,
    )
    response = await handler.handle_mcp_test(
        Request(
            {
                "transport": "STDIO",
                "connectionConfig": {"command": "python", "args": ["evil.py"]},
                "approvedCommandTemplate": {
                    "command": "python",
                    "argsPrefix": ["evil.py"],
                    "extraArgPatterns": [],
                },
            }
        )
    )

    assert response.status == 400
    assert response_json(response) == {
        "success": False,
        "errorClass": "MCPConfigurationError",
    }
    assert started is False


@pytest.mark.asyncio
async def test_network_transport_rejects_embedded_stdio_command_before_starting_process():
    handler_type = runtime_handler_type()
    started = False

    def client_factory(_):
        nonlocal started
        started = True

    handler = handler_type(
        {"manager-api": {"secret": "server-secret"}},
        plugin_registry={},
        mcp_client_factory=client_factory,
    )
    response = await handler.handle_mcp_test(
        Request(
            {
                "transport": "SSE",
                "connectionConfig": {
                    "url": "https://mcp.example.test/sse",
                    "command": "python",
                    "args": ["evil.py"],
                },
                "approvedCommandTemplate": None,
            }
        )
    )

    assert response.status == 400
    assert response_json(response)["errorClass"] == "MCPConfigurationError"
    assert started is False


@pytest.mark.asyncio
async def test_network_transport_rejects_credentials_embedded_in_url():
    handler_type = runtime_handler_type()
    started = False

    def client_factory(_):
        nonlocal started
        started = True

    handler = handler_type(
        {"manager-api": {"secret": "server-secret"}},
        plugin_registry={},
        mcp_client_factory=client_factory,
    )
    response = await handler.handle_mcp_test(
        Request(
            {
                "transport": "SSE",
                "connectionConfig": {
                    "url": "https://user:password@mcp.example.test/sse",
                },
                "approvedCommandTemplate": None,
            }
        )
    )

    assert response.status == 400
    assert response_json(response)["errorClass"] == "MCPConfigurationError"
    assert started is False
