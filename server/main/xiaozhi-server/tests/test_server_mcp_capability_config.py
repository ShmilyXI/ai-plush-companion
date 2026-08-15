import hashlib
import importlib
import json
import sys
import types
from types import SimpleNamespace

import pytest

from core.capabilities.models import CapabilityBundle
from core.providers.tools.server_mcp.config_resolver import (
    BackendMCPConfigResolver,
    MCPConfigurationError,
    filter_discovered_tools,
)


def schema_hash(schema):
    encoded = json.dumps(
        schema, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def bundle(*, transport="SSE", connection=None, template=None):
    input_schema = {
        "type": "object",
        "properties": {"query": {"type": "string"}},
        "required": ["query"],
    }
    runtime = {
        "serverId": "server-search",
        "transport": transport,
        "connectionConfig": connection
        or {"url": "https://mcp.example.test/sse", "headers": {}},
        "secretRefs": {"headers.Authorization": "secret-auth"},
        "approvedCommandTemplate": template,
        "inputSchema": input_schema,
        "schemaSha256": schema_hash(input_schema),
    }
    return CapabilityBundle.parse(
        {
            "deviceId": "device-a",
            "configVersion": 9,
            "skills": [
                {
                    "id": "skill-search",
                    "version": 1,
                    "name": "搜索",
                    "description": "MCP 搜索",
                    "executionPrompt": "调用搜索工具。",
                    "semanticThreshold": 0.7,
                    "responseMode": "LLM",
                    "timeoutMs": 30000,
                    "failureMessage": "搜索失败。",
                    "bindingPriority": 0,
                    "triggers": [],
                    "toolNames": ["mcp_search"],
                    "defaults": {},
                }
            ],
            "tools": {
                "mcp_search": {
                    "name": "mcp_search",
                    "type": "MCP",
                    "refId": "snapshot-search",
                    "alias": None,
                    "purpose": "搜索",
                    "required": True,
                    "defaults": {},
                    "runtime": runtime,
                }
            },
        }
    )


@pytest.mark.asyncio
async def test_resolves_only_device_authorized_mcp_server_and_secret_in_memory():
    requested = []

    async def secret_loader(device_id, secret_id):
        requested.append((device_id, secret_id))
        return "Bearer runtime-token"

    servers = await BackendMCPConfigResolver(secret_loader).resolve(bundle())

    assert len(servers) == 1
    assert servers[0].server_id == "server-search"
    assert servers[0].config["headers"] == {
        "Authorization": "Bearer runtime-token"
    }
    assert set(servers[0].allowed_tools) == {"mcp_search"}
    assert requested == [("device-a", "secret-auth")]


@pytest.mark.asyncio
async def test_backend_bundle_does_not_read_local_json_even_when_empty():
    called = False

    def local_loader():
        nonlocal called
        called = True
        return {"legacy": {"command": "npx"}}

    empty = CapabilityBundle.parse(
        {"deviceId": "device-a", "configVersion": 10, "skills": [], "tools": {}}
    )
    servers = await BackendMCPConfigResolver(lambda *_: None).resolve(
        empty, local_loader=local_loader
    )

    assert servers == ()
    assert called is False


@pytest.mark.asyncio
async def test_mcp_tool_without_approved_runtime_fails_closed():
    raw = {
        "deviceId": "device-a",
        "configVersion": 11,
        "skills": [
            {
                "id": "skill-search",
                "version": 1,
                "name": "搜索",
                "description": "MCP 搜索",
                "executionPrompt": "调用搜索工具。",
                "semanticThreshold": 0.7,
                "responseMode": "LLM",
                "timeoutMs": 30000,
                "failureMessage": "搜索失败。",
                "bindingPriority": 0,
                "triggers": [],
                "toolNames": ["mcp_search"],
                "defaults": {},
            }
        ],
        "tools": {
            "mcp_search": {
                "name": "mcp_search",
                "type": "MCP",
                "refId": "snapshot-search",
                "alias": None,
                "purpose": "搜索",
                "required": True,
                "defaults": {},
                "runtime": {},
            }
        },
    }
    requested = False

    async def secret_loader(*_):
        nonlocal requested
        requested = True

    servers = await BackendMCPConfigResolver(secret_loader).resolve(
        CapabilityBundle.parse(raw)
    )

    assert servers == ()
    assert requested is False


def test_backend_manager_initialization_never_probes_local_settings(monkeypatch):
    mcp_types = types.ModuleType("mcp.types")
    mcp_types.LoggingMessageNotificationParams = object
    mcp_package = types.ModuleType("mcp")
    mcp_package.types = mcp_types
    client_module = types.ModuleType("core.providers.tools.server_mcp.mcp_client")
    client_module.ServerMCPClient = object
    config_loader = types.ModuleType("config.config_loader")
    config_loader.get_project_dir = lambda: "/unused/"
    config_logger = types.ModuleType("config.logger")
    config_logger.setup_logging = lambda: SimpleNamespace(
        bind=lambda **_: SimpleNamespace(warning=lambda *_: None)
    )
    manage_api = types.ModuleType("config.manage_api_client")
    manage_api.get_capability_secret = lambda *_: None
    manage_api.report_mcp_sync = lambda *_: None
    monkeypatch.setitem(sys.modules, "mcp", mcp_package)
    monkeypatch.setitem(sys.modules, "mcp.types", mcp_types)
    monkeypatch.setitem(sys.modules, "config.config_loader", config_loader)
    monkeypatch.setitem(sys.modules, "config.logger", config_logger)
    monkeypatch.setitem(sys.modules, "config.manage_api_client", manage_api)
    monkeypatch.setitem(
        sys.modules, "core.providers.tools.server_mcp.mcp_client", client_module
    )
    manager_module = importlib.import_module(
        "core.providers.tools.server_mcp.mcp_manager"
    )
    probed = False

    def fail_if_probed(*_):
        nonlocal probed
        probed = True
        raise AssertionError("local MCP settings were probed")

    monkeypatch.setattr(manager_module.os.path, "exists", fail_if_probed)

    manager = manager_module.ServerMCPManager(SimpleNamespace(read_config_from_api=True))

    assert manager.config_path == ""
    assert probed is False


def test_new_or_schema_drifted_tools_are_not_whitelisted():
    expected = bundle().tools["mcp_search"].runtime
    resolved = type(
        "Resolved",
        (),
        {
            "allowed_tools": {
                "mcp_search": {
                    "inputSchema": expected["inputSchema"],
                    "schemaSha256": expected["schemaSha256"],
                },
                "mcp_changed": {
                    "inputSchema": expected["inputSchema"],
                    "schemaSha256": expected["schemaSha256"],
                },
            }
        },
    )()
    discovered = [
        {"name": "mcp_search", "inputSchema": expected["inputSchema"]},
        {"name": "mcp_new_tool", "inputSchema": {"type": "object"}},
        {
            "name": "mcp_changed",
            "inputSchema": {"type": "object", "properties": {}},
        },
    ]

    filtered = filter_discovered_tools(resolved, discovered)

    assert [item["name"] for item in filtered] == ["mcp_search"]


@pytest.mark.asyncio
async def test_stdio_command_must_match_approved_template():
    invalid = bundle(
        transport="STDIO",
        connection={"command": "python", "args": ["evil.py"]},
        template={"command": "npx", "argsPrefix": ["-y", "@approved/server"]},
    )

    with pytest.raises(MCPConfigurationError, match="approved command template"):
        await BackendMCPConfigResolver(lambda *_: None).resolve(invalid)


@pytest.mark.asyncio
async def test_valid_stdio_template_allows_only_matching_prefix_and_arguments():
    valid = bundle(
        transport="STDIO",
        connection={
            "command": "npx",
            "args": ["-y", "@approved/server", "/srv/mcp-data"],
        },
        template={
            "command": "npx",
            "argsPrefix": ["-y", "@approved/server"],
            "extraArgPatterns": ["^/srv/[a-z0-9-]+$"],
        },
    )

    servers = await BackendMCPConfigResolver(lambda *_: "runtime-secret").resolve(valid)

    assert servers[0].config["command"] == "npx"
