import importlib
import json
import sys
import types
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import web
from aiohttp.test_utils import make_mocked_request
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module


class DeviceControlHandlerTest(unittest.IsolatedAsyncioTestCase):
    def handler_class(self):
        try:
            module = importlib.import_module("core.api.device_control_handler")
        except ModuleNotFoundError:
            self.fail("WebSocket 设备控制处理器尚未实现")
        return module.DeviceControlHandler

    async def test_calls_mcp_tool_on_the_active_websocket_connection(self):
        mcp_client = type(
            "McpClient",
            (),
            {
                "tools": {
                    "self_audio_speaker_set_volume": {
                        "name": "self.audio_speaker.set_volume",
                        "description": "设置音量",
                        "inputSchema": {"type": "object", "properties": {}},
                    }
                },
                "is_ready": AsyncMock(return_value=True),
            },
        )()
        connection = type("Connection", (), {"mcp_client": mcp_client})()
        connection_registry = type(
            "Registry",
            (),
            {"get_connection": unittest.mock.Mock(return_value=connection)},
        )()
        handler = self.handler_class()(
            {"server": {"auth_key": "secret"}}, connection_registry
        )
        request = make_mocked_request(
            "POST",
            "/internal/device-control",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps(
            {
                "mac_address": "AA:BB:CC:DD:EE:FF",
                "method": "tools/call",
                "params": {
                    "name": "self.audio_speaker.set_volume",
                    "arguments": {"volume": 35},
                },
            }
        ).encode()

        with patch(
            "core.api.device_control_handler.call_mcp_tool",
            new=AsyncMock(return_value="true"),
        ) as call:
            response = await handler.handle_post(request)

        self.assertEqual(200, response.status)
        self.assertEqual({"success": True, "data": True}, json.loads(response.text))
        connection_registry.get_connection.assert_called_once_with(
            "AA:BB:CC:DD:EE:FF"
        )
        call.assert_awaited_once_with(
            connection,
            mcp_client,
            "self_audio_speaker_set_volume",
            {"volume": 35},
            timeout=5,
        )

    async def test_reports_unavailable_when_no_websocket_connection_exists(self):
        connection_registry = type(
            "Registry",
            (),
            {"get_connection": unittest.mock.Mock(return_value=None)},
        )()
        handler = self.handler_class()(
            {"server": {"auth_key": "secret"}}, connection_registry
        )
        request = make_mocked_request(
            "POST",
            "/internal/device-control",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps(
            {"mac_address": "AA:BB", "method": "tools/list", "params": {}}
        ).encode()

        with self.assertRaises(web.HTTPServiceUnavailable):
            await handler.handle_post(request)


if __name__ == "__main__":
    unittest.main()
