import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from aiohttp import web
from aiohttp.test_utils import make_mocked_request

from core.api.tts_preview_handler import TTSPreviewHandler
from core.providers.tts.dto.dto import InterfaceType

CONFIG = {"server": {"auth_key": "server-secret"}}
MODEL = {"type": "doubao_seedtts", "private_voice": "zh_female_wenrouxiaoya_uranus_bigtts"}


def request(body, token="server-secret"):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    mocked = make_mocked_request("POST", "/internal/tts-preview", headers=headers)
    mocked.json = AsyncMock(return_value=body)
    return mocked


def provider(audio=b"ID3audio", interface=InterfaceType.NON_STREAM, audio_format="mp3", error=None):
    speak = AsyncMock(side_effect=error) if error else AsyncMock(return_value=audio)
    return SimpleNamespace(interface_type=interface, audio_file_type=audio_format, text_to_speak=speak)


def run(handler, body, **kwargs):
    return asyncio.run(handler.handle_post(request(body, **kwargs)))


def test_synthesizes_the_sample_with_the_given_voice_and_returns_audio():
    created = []
    fake = provider()

    def factory(provider_type, model):
        created.append((provider_type, model))
        return fake

    response = run(TTSPreviewHandler(CONFIG, factory), {"model": MODEL, "text": " 你好呀 "})

    assert response.status == 200
    assert response.content_type == "audio/mpeg"
    assert response.body == b"ID3audio"
    assert created == [("doubao_seedtts", MODEL)]
    fake.text_to_speak.assert_awaited_once_with("你好呀", None)


@pytest.mark.parametrize("token", [None, "wrong"])
def test_rejects_callers_without_the_server_secret(token):
    with pytest.raises(web.HTTPUnauthorized):
        run(TTSPreviewHandler(CONFIG, lambda *_: provider()), {"model": MODEL, "text": "你好"}, token=token)


@pytest.mark.parametrize("body", [
    {"text": "你好"},
    {"model": {"voice": "x"}, "text": "你好"},
    {"model": MODEL, "text": ""},
    {"model": MODEL, "text": "长" * 81},
])
def test_rejects_malformed_requests(body):
    with pytest.raises(web.HTTPBadRequest):
        run(TTSPreviewHandler(CONFIG, lambda *_: provider()), body)


def test_streaming_providers_cannot_preview():
    fake = provider(interface=InterfaceType.DUAL_STREAM)
    response = run(TTSPreviewHandler(CONFIG, lambda *_: fake), {"model": MODEL, "text": "你好"})
    assert response.status == 422
    fake.text_to_speak.assert_not_awaited()


def test_synthesis_errors_are_not_passed_back():
    fake = provider(error=RuntimeError("HTTP 502 gateway body with details"))
    response = run(TTSPreviewHandler(CONFIG, lambda *_: fake), {"model": MODEL, "text": "你好"})
    assert response.status == 502
    assert b"gateway body" not in response.body


def test_empty_audio_is_a_failure():
    response = run(TTSPreviewHandler(CONFIG, lambda *_: provider(audio=b"")), {"model": MODEL, "text": "你好"})
    assert response.status == 502


def test_wav_audio_gets_the_wav_content_type():
    response = run(TTSPreviewHandler(CONFIG, lambda *_: provider(audio_format="wav")), {"model": MODEL, "text": "你好"})
    assert response.content_type == "audio/wav"
