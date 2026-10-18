import unittest

from core.http_server import SimpleHttpServer


class HttpServerDeviceControlRouteTest(unittest.TestCase):
    def test_registers_internal_device_control_endpoint(self):
        async def handler(request):
            return None

        server = SimpleHttpServer.__new__(SimpleHttpServer)
        server.vision_handler = type(
            "VisionHandler",
            (),
            {
                "handle_get": handler,
                "handle_post": handler,
                "handle_options": handler,
            },
        )()
        server.memory_handler = type(
            "MemoryHandler",
            (),
            {
                "handle_delete": handler,
                "handle_get": handler,
                "handle_put": handler,
                "handle_migration": handler,
            },
        )()
        server.device_control_handler = type(
            "Handler", (), {"handle_post": handler}
        )()
        server.wake_word_assets_handler = type(
            "WakeWordHandler", (), {"handle_post": handler}
        )()
        server.public_conversation_handler = type(
            "PublicConversationHandler", (), {"handle_stream": handler}
        )()

        app = server.create_app(read_config_from_api=True)

        paths = {resource.canonical for resource in app.router.resources()}
        self.assertIn("/internal/device-control", paths)
        self.assertIn("/internal/companion-memory/migration", paths)
        self.assertIn("/internal/capabilities/plugin-executors", paths)
        self.assertIn("/internal/capabilities/mcp-test", paths)


if __name__ == "__main__":
    unittest.main()
