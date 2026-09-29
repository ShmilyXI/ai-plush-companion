import asyncio
import unittest
from types import SimpleNamespace

from core.connection import ConnectionHandler
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler


def _logger_stub():
    return SimpleNamespace(
        bind=lambda **kwargs: SimpleNamespace(debug=lambda *args, **kwargs: None)
    )


def _conn_with_audio(frames):
    return SimpleNamespace(
        client_audio_buffer=[b"x"],
        client_have_voice=True,
        client_voice_stop=True,
        client_voice_window=[True],
        last_is_voice=True,
        vad_last_voice_time=123.0,
        asr_audio=list(frames),
        logger=_logger_stub(),
    )


class ResetAudioStatesPrerollTest(unittest.TestCase):
    def test_default_reset_clears_all_audio(self):
        conn = _conn_with_audio([b"f%d" % i for i in range(20)])
        ConnectionHandler.reset_audio_states(conn)
        self.assertEqual(conn.asr_audio, [])
        self.assertFalse(conn.client_have_voice)
        self.assertFalse(conn.client_voice_stop)

    def test_keep_preroll_frames_keeps_newest_frames(self):
        frames = [bytes([i]) for i in range(20)]
        conn = _conn_with_audio(frames)
        ConnectionHandler.reset_audio_states(conn, keep_preroll_frames=10)
        self.assertEqual(conn.asr_audio, frames[-10:])
        # VAD 状态仍然全量重置
        self.assertFalse(conn.client_have_voice)
        self.assertFalse(conn.client_voice_stop)
        self.assertFalse(conn.last_is_voice)
        self.assertEqual(conn.vad_last_voice_time, 0.0)


class ListenStartPrerollTest(unittest.TestCase):
    def test_listen_start_keeps_600ms_preroll(self):
        recorded = {}
        conn = SimpleNamespace(
            logger=_logger_stub(),
            reset_audio_states=lambda **kwargs: recorded.update(kwargs),
        )
        handler = ListenTextMessageHandler()
        asyncio.run(handler.handle(conn, {"state": "start", "mode": "auto"}))
        self.assertEqual(recorded.get("keep_preroll_frames"), 10)


if __name__ == "__main__":
    unittest.main()
