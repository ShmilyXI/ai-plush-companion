import asyncio

from core.http_server import SimpleHttpServer
from core.websocket_server import WebSocketServer


async def handler(request):
    return None


def test_http_router_registers_only_zixuan_product_routes():
    server = SimpleHttpServer.__new__(SimpleHttpServer)
    server.ota_handler = type(
        "OtaHandler",
        (),
        {
            "handle_get": handler,
            "handle_post": handler,
            "handle_options": handler,
            "handle_download": handler,
        },
    )()
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
    server.device_control_handler = type("Handler", (), {"handle_post": handler})()
    server.wake_word_assets_handler = type("Handler", (), {"handle_post": handler})()
    server.public_conversation_handler = type("Handler", (), {"handle_stream": handler})()
    server.playground_service = type("Playground", (), {})()

    app = server.create_app(read_config_from_api=False)
    paths = {resource.canonical for resource in app.router.resources()}
    retired = "/" + "xiao" + "zhi"

    assert "/zixuan/ota/" in paths
    assert "/zixuan/ota/download/{filename}" in paths
    assert "/zixuan/internal/playground" in paths
    assert "/api/v1/conversations/{conversation_id}/stream" in paths
    assert "/internal/device-control" in paths
    assert "/mcp/vision/explain" in paths
    assert not any(path == retired or path.startswith(retired + "/") for path in paths)


def test_websocket_upgrade_rejects_routes_outside_zixuan():
    class Socket:
        @staticmethod
        def respond(status, text):
            return status, text

    class Request:
        def __init__(self, path):
            self.path = path
            self.headers = {"connection": "upgrade"}

    server = WebSocketServer.__new__(WebSocketServer)
    retired = "/" + "xiao" + "zhi" + "/v1/"

    assert asyncio.run(server._http_response(Socket(), Request("/zixuan/v1/"))) is None
    assert asyncio.run(server._http_response(Socket(), Request(retired)))[0] == 404
    assert asyncio.run(server._http_response(Socket(), Request("/unrelated")))[0] == 404
