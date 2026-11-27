"""设备 hello 的回复里写的是服务端下发音频的参数，不是设备自己上行的参数。

以前把设备的 16000 原样回给它，服务端却按配置的 24000 编码：设备按 16000 解码，再重采样到喇叭的 24000，
真机上听着有电流声。每个连接也不能改全局配置，否则第一台设备连上后，之后的连接都按 16000 合成。
"""
import asyncio
import copy
import json
from pathlib import Path
from types import SimpleNamespace

from core.handle.helloHandle import handleHelloMessage


class FakeLogger:
    def bind(self, **_):
        return self

    def debug(self, *_):
        pass

    def info(self, *_):
        pass


class FakeSocket:
    def __init__(self):
        self.sent = []

    async def send(self, text):
        self.sent.append(json.loads(text))


SERVER_CONFIG = {"zixuan": {"type": "hello", "version": 1, "transport": "websocket",
                            "audio_params": {"format": "opus", "sample_rate": 24000, "channels": 1, "frame_duration": 60}}}


def connection(config):
    welcome = copy.deepcopy(config["zixuan"])
    return SimpleNamespace(
        welcome_msg=welcome, sample_rate=welcome["audio_params"]["sample_rate"], audio_format="opus",
        logger=FakeLogger(), websocket=FakeSocket(),
    )


def test_device_hello_gets_the_server_output_sample_rate_back():
    config = copy.deepcopy(SERVER_CONFIG)
    conn = connection(config)
    device = {"format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60}
    asyncio.run(handleHelloMessage(conn, {"type": "hello", "audio_params": device}))

    reply = conn.websocket.sent[-1]["audio_params"]
    assert reply["sample_rate"] == 24000
    assert reply["format"] == "opus"
    assert reply["frame_duration"] == 60
    # 设备自己上行的参数另外记着，解码它的声音时用。
    assert conn.client_audio_params == device
    assert conn.audio_format == "opus"
    # 全局配置没被改：下一个连接照样按 24000。
    assert config == SERVER_CONFIG


def test_a_pcm_client_is_told_pcm_at_the_server_rate():
    conn = connection(copy.deepcopy(SERVER_CONFIG))
    asyncio.run(handleHelloMessage(conn, {"type": "hello", "audio_params": {"format": "pcm", "sample_rate": 16000}}))
    reply = conn.websocket.sent[-1]["audio_params"]
    assert reply["format"] == "pcm" and reply["sample_rate"] == 24000
    assert conn.audio_format == "pcm"


def test_each_connection_builds_its_welcome_message_from_a_copy_of_the_config():
    source = (Path(__file__).parents[1] / "core/connection.py").read_text(encoding="utf-8")
    assert 'self.welcome_msg = copy.deepcopy(self.config["zixuan"])' in source
    assert 'self.welcome_msg = self.config["zixuan"]\n' not in source
