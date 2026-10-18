import asyncio
import secrets
from aiohttp import web
from config.logger import setup_logging
from config.product_identity import (
    OTA_DOWNLOAD_ROUTE,
    OTA_ROUTE,
    PLAYGROUND_ROUTE,
    WEBSOCKET_ROUTE,
)
from core.api.ota_handler import OTAHandler
from core.api.vision_handler import VisionHandler
from core.api.companion_memory_handler import CompanionMemoryHandler
from core.api.device_control_handler import DeviceControlHandler
from core.api.capability_runtime_handler import CapabilityRuntimeHandler
from core.api.wake_word_assets_handler import WakeWordAssetsHandler
from core.wake_word.generator import WakeWordAssetGenerator
from pathlib import Path
from core.playground.service import PlaygroundService
from core.public_conversation.service import PublicConversationService
from core.api.public_conversation_handler import PublicConversationHandler

TAG = __name__


class SimpleHttpServer:
    def __init__(self, config: dict, connection_registry):
        self.config = config
        self.logger = setup_logging()
        self.ota_handler = OTAHandler(config)
        self.vision_handler = VisionHandler(config)
        self.memory_handler = CompanionMemoryHandler(config)
        self.device_control_handler = DeviceControlHandler(config, connection_registry)
        self.capability_runtime_handler = CapabilityRuntimeHandler(config)
        model_dir = Path(__file__).resolve().parents[1] / "models" / "wake_word" / "mn7_cn"
        self.wake_word_assets_handler = WakeWordAssetsHandler(
            config, WakeWordAssetGenerator(model_dir)
        )
        self.playground_service = PlaygroundService()
        self.public_conversation_handler = PublicConversationHandler(PublicConversationService(config))

    def _get_websocket_url(self, local_ip: str, port: int) -> str:
        """获取websocket地址

        Args:
            local_ip: 本地IP地址
            port: 端口号

        Returns:
            str: websocket地址
        """
        server_config = self.config["server"]
        websocket_config = server_config.get("websocket")

        if websocket_config and "你" not in websocket_config:
            return websocket_config
        else:
            return f"ws://{local_ip}:{port}{WEBSOCKET_ROUTE}"

    def create_app(self, read_config_from_api):
        app = web.Application()
        if not read_config_from_api:
            app.add_routes(
                [
                    web.get(OTA_ROUTE, self.ota_handler.handle_get),
                    web.post(OTA_ROUTE, self.ota_handler.handle_post),
                    web.options(OTA_ROUTE, self.ota_handler.handle_options),
                    web.get(
                        OTA_DOWNLOAD_ROUTE,
                        self.ota_handler.handle_download,
                    ),
                    web.options(
                        OTA_DOWNLOAD_ROUTE,
                        self.ota_handler.handle_options,
                    ),
                ]
            )
        app.add_routes(
            [
                web.get("/mcp/vision/explain", self.vision_handler.handle_get),
                web.post("/mcp/vision/explain", self.vision_handler.handle_post),
                web.options("/mcp/vision/explain", self.vision_handler.handle_options),
                web.delete(
                    "/internal/companion-memory", self.memory_handler.handle_delete
                ),
                web.post(
                    "/internal/companion-memory/migration", self.memory_handler.handle_migration
                ),
                web.get("/internal/companion-memory", self.memory_handler.handle_get),
                web.put("/internal/companion-memory", self.memory_handler.handle_put),
                web.post(
                    "/internal/device-control",
                    self.device_control_handler.handle_post,
                ),
                web.get(
                    "/internal/capabilities/plugin-executors",
                    self._handle_plugin_executors,
                ),
                web.post(
                    "/internal/capabilities/mcp-test",
                    self._handle_mcp_test,
                ),
                web.post(
                    "/internal/wake-word-assets",
                    self.wake_word_assets_handler.handle_post,
                ),
            ]
        )
        app.add_routes([
            web.post(PLAYGROUND_ROUTE, self._playground_create),
            web.post(f"{PLAYGROUND_ROUTE}/{{session_id}}/inputs", self._playground_input),
            web.get(f"{PLAYGROUND_ROUTE}/{{session_id}}/events", self._playground_events),
            web.delete(f"{PLAYGROUND_ROUTE}/{{session_id}}", self._playground_close),
            web.get("/api/v1/conversations/{conversation_id}/stream", self.public_conversation_handler.handle_stream),
        ])
        return app

    def _playground_authorized(self, request):
        configured = self.config.get("server", {}).get("auth_key", "")
        authorization = request.headers.get("Authorization", "")
        token = authorization[7:] if authorization.startswith("Bearer ") else ""
        return bool(configured) and secrets.compare_digest(token, configured)

    async def _playground_create(self, request):
        if not self._playground_authorized(request): return web.json_response({"error": "unauthorized"}, status=401)
        return await self.playground_service.handle_create(request)

    async def _playground_input(self, request):
        if not self._playground_authorized(request): return web.json_response({"error": "unauthorized"}, status=401)
        return await self.playground_service.handle_input(request)

    async def _playground_events(self, request):
        if not self._playground_authorized(request): return web.json_response({"error": "unauthorized"}, status=401)
        return await self.playground_service.handle_events(request)

    async def _playground_close(self, request):
        if not self._playground_authorized(request): return web.json_response({"error": "unauthorized"}, status=401)
        return await self.playground_service.handle_close(request)

    async def _handle_plugin_executors(self, request):
        return await self.capability_runtime_handler.handle_plugin_executors(request)

    async def _handle_mcp_test(self, request):
        return await self.capability_runtime_handler.handle_mcp_test(request)

    async def start(self):
        try:
            server_config = self.config["server"]
            read_config_from_api = self.config.get("read_config_from_api", False)
            host = server_config.get("ip", "0.0.0.0")
            port = int(server_config.get("http_port", 8003))

            if port:
                app = self.create_app(read_config_from_api)

                # 运行服务
                runner = web.AppRunner(app)
                await runner.setup()
                site = web.TCPSite(runner, host, port)
                await site.start()

                # 保持服务运行
                while True:
                    await asyncio.sleep(3600)  # 每隔 1 小时检查一次
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"HTTP服务器启动失败: {e}")
            import traceback

            self.logger.bind(tag=TAG).error(f"错误堆栈: {traceback.format_exc()}")
            raise
