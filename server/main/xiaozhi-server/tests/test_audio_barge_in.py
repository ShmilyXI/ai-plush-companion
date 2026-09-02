import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

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

    def test_explicit_abort_refreshes_companion_activity(self):
        from core.handle.abortHandle import handleAbortMessage

        conn = SimpleNamespace(
            logger=SimpleNamespace(bind=lambda **_kwargs: SimpleNamespace(info=lambda *_args: None)),
            notify_confirmed_user_activity=Mock(),
            proactive_playback_active=False,
            tts=None,
            websocket=SimpleNamespace(send=AsyncMock()),
            client_abort=False,
            client_aec=False,
            client_listen_mode="realtime",
            close_after_chat=False,
            sentence_id=None,
            _fail_llm_debug=Mock(),
            clear_queues=Mock(),
            clearSpeakStatus=Mock(),
            emit_debug_event=Mock(),
            session_id="session",
        )

        asyncio.run(handleAbortMessage(conn))

        conn.notify_confirmed_user_activity.assert_called_once()


if __name__ == "__main__":
    unittest.main()
