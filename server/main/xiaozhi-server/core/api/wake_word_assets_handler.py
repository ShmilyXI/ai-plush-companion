import asyncio
import hmac

from aiohttp import web

from core.wake_word.generator import WakeWordAssetGenerator, WakeWordRequest


class WakeWordAssetsHandler:
    def __init__(self, config, generator: WakeWordAssetGenerator):
        self.config = config
        self.generator = generator

    async def handle_post(self, request):
        self._authenticate(request)
        body = await self._json_body(request)
        build_request = WakeWordRequest(
            device_id=self._required_text(body, "device_id"),
            word=self._required_text(body, "word", strip=False),
            version=self._required_integer(body, "version"),
            chip=self._required_text(body, "chip"),
            slot_size=self._required_integer(body, "slot_size"),
        )
        try:
            result = await asyncio.to_thread(self.generator.generate, build_request)
        except ValueError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        return web.Response(
            body=result.content,
            content_type="application/octet-stream",
            headers={
                "X-Wake-Word-Sha256": result.sha256,
                "X-Wake-Word-Size": str(result.size),
                "X-Wake-Word-Version": str(result.version),
            },
        )

    def _authenticate(self, request):
        token = request.headers.get("Authorization", "").removeprefix("Bearer ")
        expected = self.config.get("server", {}).get("auth_key") or self.config.get(
            "manager-api", {}
        ).get("secret", "")
        if not token or not expected or not hmac.compare_digest(token, expected):
            raise web.HTTPUnauthorized()

    @staticmethod
    async def _json_body(request):
        try:
            body = await request.json()
        except Exception as exc:
            raise web.HTTPBadRequest(text="invalid JSON body") from exc
        if not isinstance(body, dict):
            raise web.HTTPBadRequest(text="invalid JSON body")
        return body

    @staticmethod
    def _required_text(body, key, *, strip=True):
        value = body.get(key)
        if not isinstance(value, str) or not value.strip():
            raise web.HTTPBadRequest(text=f"invalid {key}")
        return value.strip() if strip else value

    @staticmethod
    def _required_integer(body, key):
        value = body.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise web.HTTPBadRequest(text=f"invalid {key}")
        return value
