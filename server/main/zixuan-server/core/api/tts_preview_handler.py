"""音色试听：manager-api 交来一份语音合成配置和一句话，这里合成后原样返回音频。

只给 manager-api 用，按服务端密钥鉴权。配置里的地址和密钥由 manager-api 和本服务的环境变量决定，
浏览器只能指定音色 ID，不能指定配置或文本，所以这里不会被拿去合成任意内容。
"""

import asyncio
import hmac

from aiohttp import web

from config.logger import setup_logging
from core.providers.tts.dto.dto import InterfaceType
from core.utils import tts

TAG = __name__
logger = setup_logging()

MAX_TEXT_CHARS = 80
MAX_AUDIO_BYTES = 2 * 1024 * 1024
SYNTHESIS_TIMEOUT_SECONDS = 30
CONTENT_TYPES = {
    "mp3": "audio/mpeg",
    "wav": "audio/wav",
    "ogg": "audio/ogg",
    "opus": "audio/ogg",
    "aac": "audio/aac",
    "flac": "audio/flac",
}


class TTSPreviewHandler:
    def __init__(self, config, provider_factory=None):
        self.config = config
        self._create = provider_factory or (
            lambda provider_type, model: tts.create_instance(provider_type, model, True)
        )

    async def handle_post(self, request):
        self._authenticate(request)
        body = await self._json_body(request)
        model = body.get("model")
        text = body.get("text")
        if not isinstance(model, dict) or not isinstance(model.get("type"), str) or not model["type"]:
            raise web.HTTPBadRequest(text="model.type is required")
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
            raise web.HTTPBadRequest(text="text is required and must be short")
        try:
            provider = self._create(model["type"], dict(model))
        except Exception as error:
            logger.bind(tag=TAG).warning(f"试听无法创建语音合成: {type(error).__name__}")
            return web.json_response({"error": "这个声音暂时不能试听"}, status=422)
        if getattr(provider, "interface_type", InterfaceType.NON_STREAM) != InterfaceType.NON_STREAM:
            # 双向流式的合成需要一条对话连接，单独合成一句拿不到完整音频。
            return web.json_response({"error": "这个声音暂时不能试听"}, status=422)
        try:
            audio = await asyncio.wait_for(
                provider.text_to_speak(text.strip(), None), SYNTHESIS_TIMEOUT_SECONDS
            )
        except Exception as error:
            # 错误信息可能带网关返回的正文，只记类型，不回给调用方。
            logger.bind(tag=TAG).warning(f"试听合成失败: {model['type']} {type(error).__name__}")
            return web.json_response({"error": "试听合成失败，请稍后再试"}, status=502)
        if not isinstance(audio, (bytes, bytearray)) or not audio or len(audio) > MAX_AUDIO_BYTES:
            return web.json_response({"error": "试听合成失败，请稍后再试"}, status=502)
        audio_format = str(getattr(provider, "audio_file_type", "mp3") or "mp3").lower()
        return web.Response(
            body=bytes(audio),
            content_type=CONTENT_TYPES.get(audio_format, "application/octet-stream"),
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
        except Exception as error:
            raise web.HTTPBadRequest(text="invalid json") from error
        if not isinstance(body, dict):
            raise web.HTTPBadRequest(text="invalid json")
        return body
