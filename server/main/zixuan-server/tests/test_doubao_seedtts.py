import asyncio
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from core.providers.tts import doubao_seedtts
from core.providers.tts.doubao_seedtts import TTSProvider, resolve_endpoint

ENV = {
    "ZIXUAN_DOUBAO_TTS_API_URL": "https://gateway.example/v1",
    "ZIXUAN_DOUBAO_TTS_API_KEY": "platform-secret",
}


def make_provider(monkeypatch, env=ENV, **config):
    for name in ("ZIXUAN_DOUBAO_TTS_API_URL", "ZIXUAN_DOUBAO_TTS_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    return TTSProvider({"output_dir": "tmp/", **config}, True)


def response(status=200, content=b"ID3audio", content_type="audio/mpeg", text=""):
    return SimpleNamespace(
        status_code=status, content=content, headers={"Content-Type": content_type}, text=text
    )


def test_platform_key_comes_with_platform_url():
    url, key, source = resolve_endpoint({}, ENV)
    assert (url, key, source) == ("https://gateway.example/v1/audio/speech", "platform-secret", "env")


def test_config_url_cannot_redirect_platform_key():
    url, key, source = resolve_endpoint({"api_url": "https://attacker.example/v1"}, ENV)
    assert url == "https://gateway.example/v1/audio/speech"
    assert key == "platform-secret"
    assert source == "env"


def test_own_key_and_url_are_used_as_a_pair():
    config = {"api_url": "https://own.example/v1/audio/speech", "api_key": "own-key"}
    assert resolve_endpoint(config, ENV) == ("https://own.example/v1/audio/speech", "own-key", "config")


@pytest.mark.parametrize("env", [{}, {"ZIXUAN_DOUBAO_TTS_API_KEY": "k"}, {"ZIXUAN_DOUBAO_TTS_API_URL": "https://g/v1"}])
def test_missing_half_of_the_platform_pair_means_not_configured(env):
    assert resolve_endpoint({"api_key": "你的密钥"}, env) == (None, None, None)


def test_role_voice_wins_and_request_carries_seedtts_payload(monkeypatch):
    provider = make_provider(monkeypatch, voice="zh_female_vv_uranus_bigtts",
                             private_voice="zh_male_m191_uranus_bigtts")
    with patch.object(doubao_seedtts.requests, "post", return_value=response()) as post:
        audio = asyncio.run(provider.text_to_speak("你好", None))

    assert audio == b"ID3audio"
    args, kwargs = post.call_args
    assert args[0] == "https://gateway.example/v1/audio/speech"
    assert kwargs["json"] == {
        "model": "seed-tts-2.0",
        "input": "你好",
        "voice": "zh_male_m191_uranus_bigtts",
        "response_format": "mp3",
    }
    assert kwargs["headers"]["Authorization"] == "Bearer platform-secret"
    assert "Mozilla" in kwargs["headers"]["User-Agent"]
    assert kwargs["timeout"] > 0


def test_speed_is_sent_only_when_changed(monkeypatch):
    provider = make_provider(monkeypatch, speed=1.2)
    with patch.object(doubao_seedtts.requests, "post", return_value=response()) as post:
        asyncio.run(provider.text_to_speak("你好", None))
    assert post.call_args.kwargs["json"]["speed"] == 1.2


def test_writes_audio_file(monkeypatch, tmp_path):
    provider = make_provider(monkeypatch)
    target = tmp_path / "out.mp3"
    with patch.object(doubao_seedtts.requests, "post", return_value=response()):
        assert asyncio.run(provider.text_to_speak("你好", str(target))) is None
    assert target.read_bytes() == b"ID3audio"


def test_gateway_errors_are_readable_and_never_contain_the_key(monkeypatch):
    provider = make_provider(monkeypatch)
    failure = response(502, b"", "application/json", '{"error":"resource ID is mismatched"}')
    with patch.object(doubao_seedtts.requests, "post", return_value=failure):
        with pytest.raises(RuntimeError) as error:
            asyncio.run(provider.text_to_speak("你好", None))
    assert "HTTP 502" in str(error.value)
    assert "mismatched" in str(error.value)
    assert "platform-secret" not in str(error.value)


def test_json_body_with_200_is_not_treated_as_audio(monkeypatch):
    provider = make_provider(monkeypatch)
    with patch.object(doubao_seedtts.requests, "post",
                      return_value=response(content=b'{"error":"x"}', content_type="application/json")):
        with pytest.raises(RuntimeError, match="没有返回音频"):
            asyncio.run(provider.text_to_speak("你好", None))


def test_unconfigured_provider_fails_without_calling_the_network(monkeypatch):
    provider = make_provider(monkeypatch, env={})
    with patch.object(doubao_seedtts.requests, "post") as post:
        with pytest.raises(RuntimeError, match="未配置"):
            asyncio.run(provider.text_to_speak("你好", None))
    post.assert_not_called()
