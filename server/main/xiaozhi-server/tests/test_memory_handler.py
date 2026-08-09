import json
import sys
import types
import unittest
from unittest.mock import AsyncMock

from aiohttp import web
from aiohttp.test_utils import make_mocked_request
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module

from core.api.memory_handler import MemoryHandler


class MemoryHandlerTest(unittest.IsolatedAsyncioTestCase):
    async def test_valid_server_secret_clears_bound_namespace(self):
        provider = type("Provider", (), {"clear_memory": AsyncMock(return_value=True)})()
        memory_factory = unittest.mock.Mock(return_value=provider)
        handler = MemoryHandler(
            {"server": {"auth_key": "secret"}, "read_config_from_api": True},
            config_loader=AsyncMock(return_value={
                "companion_identity": {
                    "user_id": 7,
                    "agent_id": "agent-id",
                    "device_id": "device-id",
                    "memory_namespace": "companion:" + "a" * 64,
                }
            }),
            memory_factory=memory_factory,
        )
        request = make_mocked_request(
            "DELETE",
            "/internal/companion-memory",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps({
            "mac_address": "AA:BB",
            "client_id": "manager-api",
        }).encode()

        response = await handler.handle_delete(request)

        self.assertEqual(200, response.status)
        provider.clear_memory.assert_awaited_once()
        self.assertFalse(memory_factory.call_args.args[2])

    async def test_local_mode_clears_matching_device_namespace_on_disk(self):
        namespace = "companion:" + "b" * 64
        provider = type("Provider", (), {"clear_memory": AsyncMock(return_value=True)})()
        config_loader = AsyncMock()
        memory_factory = unittest.mock.Mock(return_value=provider)
        handler = MemoryHandler(
            {
                "server": {"auth_key": "secret"},
                "read_config_from_api": False,
                "companion_identity": {
                    "user_id": 7,
                    "agent_id": "local-agent",
                    "device_id": "aa:bb:cc:dd:ee:ff",
                    "memory_namespace": namespace,
                },
            },
            config_loader=config_loader,
            memory_factory=memory_factory,
        )
        request = make_mocked_request(
            "DELETE",
            "/internal/companion-memory",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps({
            "mac_address": "AA:BB:CC:DD:EE:FF",
            "client_id": "manager-api",
        }).encode()

        response = await handler.handle_delete(request)

        self.assertEqual(200, response.status)
        config_loader.assert_not_awaited()
        self.assertEqual(handler.config, memory_factory.call_args.args[0])
        self.assertEqual(namespace, memory_factory.call_args.args[1])
        self.assertTrue(memory_factory.call_args.args[2])
        provider.clear_memory.assert_awaited_once()

    async def test_local_mode_rejects_a_different_device_id(self):
        memory_factory = unittest.mock.Mock()
        handler = MemoryHandler(
            {
                "server": {"auth_key": "secret"},
                "read_config_from_api": False,
                "companion_identity": {
                    "user_id": 7,
                    "agent_id": "local-agent",
                    "device_id": "aa:bb:cc:dd:ee:ff",
                    "memory_namespace": "companion:" + "c" * 64,
                },
            },
            config_loader=AsyncMock(),
            memory_factory=memory_factory,
        )
        request = make_mocked_request(
            "DELETE",
            "/internal/companion-memory",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps({
            "mac_address": "11:22:33:44:55:66",
        }).encode()

        with self.assertRaises(web.HTTPForbidden):
            await handler.handle_delete(request)

        memory_factory.assert_not_called()


if __name__ == "__main__":
    unittest.main()
