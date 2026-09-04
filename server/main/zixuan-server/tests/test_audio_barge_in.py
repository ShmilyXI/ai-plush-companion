import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from core.handle.receiveAudioHandle import handleAudioMessage


class AudioBargeInTest(unittest.TestCase):
    def test_aec_voice_can_interrupt_during_post_wakeup_guard(self):
        conn = SimpleNamespace(
            vad=SimpleNamespace(is_vad=lambda _conn, _pcm: True),
            just_woken_up=True,
            client_aec=True,
            client_is_speaking=True,
            client_listen_mode="realtime",
            asr=SimpleNamespace(receive_audio=AsyncMock()),
        )

        with patch(
            "core.handle.receiveAudioHandle.handleAbortMessage",
            new=AsyncMock(),
        ) as abort, patch(
            "core.handle.receiveAudioHandle.no_voice_close_connect",
            new=AsyncMock(),
        ):
            asyncio.run(handleAudioMessage(conn, b"pcm"))

        abort.assert_awaited_once_with(conn)
        conn.asr.receive_audio.assert_awaited_once_with(conn, b"pcm", True)


if __name__ == "__main__":
    unittest.main()
