import json
import unittest

from aiohttp import web
from aiohttp.test_utils import make_mocked_request

from core.api.wake_word_assets_handler import WakeWordAssetsHandler
from core.wake_word.generator import WakeWordBuild


class FakeGenerator:
    def __init__(self):
        self.request = None

    def generate(self, request):
        self.request = request
        return WakeWordBuild(b"assets", "a" * 64, 6, request.word.strip(), request.version)


class WakeWordAssetsHandlerTest(unittest.IsolatedAsyncioTestCase):
    async def test_rejects_missing_bearer_auth(self):
        handler = WakeWordAssetsHandler({"server": {"auth_key": "secret"}}, FakeGenerator())
        request = make_mocked_request("POST", "/internal/wake-word-assets")
        request._read_bytes = b"{}"

        with self.assertRaises(web.HTTPUnauthorized):
            await handler.handle_post(request)

    async def test_rejects_invalid_json(self):
        handler = WakeWordAssetsHandler({"server": {"auth_key": "secret"}}, FakeGenerator())
        request = make_mocked_request(
            "POST",
            "/internal/wake-word-assets",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = b"not-json"

        with self.assertRaises(web.HTTPBadRequest):
            await handler.handle_post(request)

    async def test_returns_binary_package_and_verified_headers(self):
        generator = FakeGenerator()
        handler = WakeWordAssetsHandler({"server": {"auth_key": "secret"}}, generator)
        request = make_mocked_request(
            "POST",
            "/internal/wake-word-assets",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps(
            {
                "device_id": "device-1",
                "word": " 小布小布 ",
                "version": 7,
                "chip": "esp32s3",
                "slot_size": 0x300000,
            }
        ).encode()

        response = await handler.handle_post(request)

        self.assertEqual(200, response.status)
        self.assertEqual(b"assets", response.body)
        self.assertEqual("application/octet-stream", response.content_type)
        self.assertEqual("a" * 64, response.headers["X-Wake-Word-Sha256"])
        self.assertEqual("6", response.headers["X-Wake-Word-Size"])
        self.assertEqual("7", response.headers["X-Wake-Word-Version"])
        self.assertEqual("device-1", generator.request.device_id)

    async def test_rejects_boolean_integer_fields(self):
        handler = WakeWordAssetsHandler({"server": {"auth_key": "secret"}}, FakeGenerator())
        request = make_mocked_request(
            "POST",
            "/internal/wake-word-assets",
            headers={"Authorization": "Bearer secret"},
        )
        request._read_bytes = json.dumps(
            {"device_id": "device-1", "word": "小布小布", "version": True, "chip": "esp32s3", "slot_size": 1}
        ).encode()

        with self.assertRaises(web.HTTPBadRequest):
            await handler.handle_post(request)


if __name__ == "__main__":
    unittest.main()
