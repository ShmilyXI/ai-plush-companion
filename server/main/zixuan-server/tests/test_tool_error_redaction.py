from types import SimpleNamespace

import pytest

from plugins_func.register import Action, all_function_registry
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from core.providers.tools.server_mcp.mcp_executor import ServerMCPExecutor
from core.providers.tools.device_mcp import mcp_executor as device_mcp_module
from core.providers.tools.mcp_endpoint import mcp_endpoint_executor as endpoint_module


def assert_redacted_failure(result):
    assert result.action == Action.ERROR
    assert "secret" not in str(result.response).lower()


@pytest.mark.asyncio
async def test_plugin_executor_does_not_return_exception_text():
    def fail(**_arguments):
        raise RuntimeError("token=plugin-secret")

    item = SimpleNamespace(type=SimpleNamespace(code=1), func=fail)
    original = dict(all_function_registry)
    all_function_registry.clear()
    all_function_registry["unsafe_plugin"] = item
    try:
        result = await ServerPluginExecutor(SimpleNamespace(config={})).execute(
            SimpleNamespace(), "unsafe_plugin", {}
        )
    finally:
        all_function_registry.clear()
        all_function_registry.update(original)

    assert_redacted_failure(result)


@pytest.mark.asyncio
async def test_server_mcp_executor_does_not_return_exception_text():
    class Manager:
        async def execute_tool(self, _name, _arguments):
            raise RuntimeError("authorization=server-mcp-secret")

    executor = ServerMCPExecutor(SimpleNamespace())
    executor._initialized = True
    executor.mcp_manager = Manager()

    result = await executor.execute(SimpleNamespace(), "mcp_weather", {})

    assert_redacted_failure(result)


@pytest.mark.asyncio
async def test_device_mcp_executor_does_not_return_exception_text(monkeypatch):
    async def fail(*_args, **_kwargs):
        raise RuntimeError("credential=device-mcp-secret")

    monkeypatch.setattr(device_mcp_module, "call_mcp_tool", fail)
    client = SimpleNamespace(is_ready=lambda: _return_async(True))
    conn = SimpleNamespace(mcp_client=client)

    result = await device_mcp_module.DeviceMCPExecutor(conn).execute(
        conn, "self.audio_speaker.set_volume", {}
    )

    assert_redacted_failure(result)


@pytest.mark.asyncio
async def test_mcp_endpoint_executor_does_not_return_exception_text(monkeypatch):
    async def fail(*_args, **_kwargs):
        raise RuntimeError("password=endpoint-secret")

    monkeypatch.setattr(endpoint_module, "call_mcp_endpoint_tool", fail)
    client = SimpleNamespace(is_ready=lambda: _return_async(True))
    conn = SimpleNamespace(mcp_endpoint_client=client)

    result = await endpoint_module.MCPEndpointExecutor(conn).execute(
        conn, "remote_tool", {}
    )

    assert_redacted_failure(result)


async def _return_async(value):
    return value
