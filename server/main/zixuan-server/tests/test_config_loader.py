import unittest
from unittest.mock import AsyncMock, patch

from config import config_loader
from core.utils.cache.manager import CacheType, cache_manager


class ManagerApiConfigLoaderTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        cache_manager.clear(CacheType.CONFIG)

    def tearDown(self):
        cache_manager.clear(CacheType.CONFIG)

    async def _load_manager_api_config(self, api_companion):
        real_read_config = config_loader.read_config

        def read_config_without_private_file(config_path):
            if config_path.endswith("data/.config.yaml"):
                return {"manager-api": {"url": "http://manager.test"}}
            return real_read_config(config_path)

        api_config = {
            "server": {"auth": {"enabled": False}},
            "companion": api_companion,
        }
        with (
            patch.object(
                config_loader,
                "read_config",
                side_effect=read_config_without_private_file,
            ),
            patch.object(
                config_loader,
                "get_config_from_api_async",
                new=AsyncMock(return_value=api_config),
            ),
            patch.object(config_loader, "ensure_directories"),
        ):
            return await config_loader.load_config()

    async def test_manager_api_companion_uses_default_persona_when_missing(self):
        config = await self._load_manager_api_config({"enabled": True})

        self.assertTrue(config["companion"]["enabled"])
        self.assertIn("persona_prompt", config["companion"])
        self.assertIn("治愈型陪伴朋友", config["companion"]["persona_prompt"])
        self.assertIn("cue_files", config["companion"])
        self.assertNotIn("delete_audio", config)

    async def test_manager_api_companion_overrides_default_persona(self):
        config = await self._load_manager_api_config(
            {"enabled": True, "persona_prompt": "API 自定义角色"}
        )

        self.assertEqual("API 自定义角色", config["companion"]["persona_prompt"])

    async def test_manager_api_config_preserves_local_public_server_urls(self):
        local_config = {
            "manager-api": {
                "url": "http://manager.test",
                "secret": "test-secret",
            },
            "server": {
                "ip": "0.0.0.0",
                "port": 8000,
                "http_port": 8003,
                "websocket": "ws://192.168.0.102:8000/zixuan/v1/",
                "vision_explain": "http://192.168.0.102:8003/mcp/vision/explain",
                "auth_key": "test-auth-key",
            },
        }
        api_config = {
            "server": {
                "websocket": "ws://api.example/zixuan/v1/",
                "auth": {"enabled": True},
            }
        }

        with (
            patch.object(config_loader, "init_service"),
            patch.object(
                config_loader,
                "get_server_config",
                new=AsyncMock(return_value=api_config),
            ),
        ):
            config = await config_loader.get_config_from_api_async(local_config)

        self.assertEqual(
            "ws://192.168.0.102:8000/zixuan/v1/",
            config["server"]["websocket"],
        )
        self.assertEqual(
            "http://192.168.0.102:8003/mcp/vision/explain",
            config["server"]["vision_explain"],
        )
        self.assertTrue(config["server"]["auth"]["enabled"])


if __name__ == "__main__":
    unittest.main()
