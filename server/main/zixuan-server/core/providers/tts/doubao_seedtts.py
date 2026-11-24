"""豆包语音 SeedTTS 2.0，走 OpenAI 兼容的 /audio/speech 网关。

和 doubao.py（火山引擎 v1 接口，appid + access_token + cluster）不同，
这里只要一个网关地址和一把 Bearer 密钥，模型是 seed-tts-2.0，
音色用 SeedTTS 2.0 的 *_uranus_bigtts 系列。

平台的地址和密钥只从 zixuan-server 的环境变量读取：
ZIXUAN_DOUBAO_TTS_API_URL、ZIXUAN_DOUBAO_TTS_API_KEY。数据库、管理台、浏览器和设备里都没有这把密钥。
平台密钥只发往环境变量里的地址：模型配置或角色覆盖项里的 api_url 不能把它带去别处。
只有配置里同时给了自己的 api_key 和 api_url，才按配置里的这一对发请求。
"""

import asyncio
import os

import requests

from config.logger import setup_logging
from core.providers.tts.base import TTSProviderBase

TAG = __name__
logger = setup_logging()

DEFAULT_MODEL = "seed-tts-2.0"
DEFAULT_VOICE = "zh_female_vv_uranus_bigtts"
API_KEY_ENV = "ZIXUAN_DOUBAO_TTS_API_KEY"
API_URL_ENV = "ZIXUAN_DOUBAO_TTS_API_URL"
# 部分网关前面有 Cloudflare，requests 默认的 UA 会被拦（error 1010）。
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)


def _usable(value):
    """空值、占位值（“你的…”）、EMPTY 都当作没填。"""
    if not isinstance(value, str):
        return None
    value = value.strip()
    if not value or "你" in value or value.upper() == "EMPTY":
        return None
    return value


def _speech_url(value):
    """接受完整的 .../audio/speech，也接受只写到 /v1 的网关地址。"""
    url = value.rstrip("/")
    return url if url.endswith("/audio/speech") else f"{url}/audio/speech"


def resolve_endpoint(config, environ=None):
    """返回 (api_url, api_key, source)。source 是 config、env 或 None。"""
    environ = os.environ if environ is None else environ
    own_key = _usable(config.get("api_key"))
    own_url = _usable(config.get("api_url"))
    if own_key and own_url:
        return _speech_url(own_url), own_key, "config"
    env_key = _usable(environ.get(API_KEY_ENV))
    env_url = _usable(environ.get(API_URL_ENV))
    if env_key and env_url:
        return _speech_url(env_url), env_key, "env"
    return None, None, None


class TTSProvider(TTSProviderBase):
    TTS_PARAM_CONFIG = [
        ("ttsRate", "speed", 0.5, 2.0, 1.0, lambda v: round(float(v), 2)),
    ]

    def __init__(self, config, delete_audio_file):
        super().__init__(config, delete_audio_file)
        self.api_url, self.api_key, self.credential_source = resolve_endpoint(config)
        self.model = _usable(config.get("model")) or DEFAULT_MODEL
        self.voice = (
            _usable(config.get("private_voice"))
            or _usable(config.get("voice"))
            or DEFAULT_VOICE
        )
        self.audio_file_type = _usable(config.get("format")) or "mp3"
        speed = config.get("speed", 1.0)
        self.speed = float(speed) if speed not in (None, "") else 1.0
        self._apply_percentage_params(config)
        self.request_timeout = float(config.get("request_timeout", 30))
        if not self.api_key:
            # 只说缺了什么，不打印任何密钥内容。
            logger.bind(tag=TAG).error(
                f"豆包语音没有可用的地址和密钥：请在 zixuan-server 设置环境变量 {API_URL_ENV} 和 {API_KEY_ENV}"
            )
        elif self.credential_source == "env" and _usable(config.get("api_url")):
            logger.bind(tag=TAG).warning("豆包语音使用平台密钥，忽略模型配置里的 api_url")

    def _payload(self, text):
        payload = {
            "model": self.model,
            "input": text,
            "voice": self.voice,
            "response_format": self.audio_file_type,
        }
        if abs(self.speed - 1.0) > 1e-6:
            payload["speed"] = self.speed
        return payload

    def _request(self, text):
        if not self.api_key or not self.api_url:
            raise RuntimeError("豆包语音未配置地址和密钥")
        response = requests.post(
            self.api_url,
            json=self._payload(text),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "User-Agent": USER_AGENT,
            },
            timeout=self.request_timeout,
        )
        if response.status_code != 200:
            # 网关返回的正文只截一小段，便于排查音色不匹配等问题；请求头不进错误信息。
            detail = (response.text or "")[:200]
            raise RuntimeError(f"豆包语音请求失败: HTTP {response.status_code} {detail}")
        content_type = response.headers.get("Content-Type", "")
        if not response.content or content_type.startswith("application/json"):
            detail = (response.text or "")[:200]
            raise RuntimeError(f"豆包语音没有返回音频: {detail}")
        logger.bind(tag=TAG).info(
            f"豆包语音合成完成: 音色 {self.voice}，{len(text)} 字，{len(response.content)} 字节"
        )
        return response.content

    async def text_to_speak(self, text, output_file):
        audio = await asyncio.to_thread(self._request, text)
        if output_file:
            with open(output_file, "wb") as audio_file:
                audio_file.write(audio)
            return None
        return audio
