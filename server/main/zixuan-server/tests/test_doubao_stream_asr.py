import asyncio
import inspect
import unittest

from core.providers.asr.doubao_stream import ASRProvider


class _FakeConnection:
    def __init__(self):
        self.client_listen_mode = "auto"
        self.client_voice_stop = False
        self.asr_audio = [b"frame"] * 16
        self.stop_event = asyncio.Event()
        self.voice_stop_calls = []

    def reset_audio_states(self):
        pass


class _FakeWebSocket:
    def __init__(self, responses, conn):
        self.responses = iter(responses)
        self.conn = conn

    async def recv(self):
        response = next(self.responses)
        if callable(response):
            response = response()
        if inspect.isawaitable(response):
            response = await response
        return response

    async def close(self):
        pass


class DoubaoStreamAsrTest(unittest.IsolatedAsyncioTestCase):
    def _provider(self, responses, conn):
        provider = ASRProvider.__new__(ASRProvider)
        provider.asr_ws = _FakeWebSocket(responses, conn)
        provider.is_processing = True
        provider._is_stopping = False
        provider.text = ""
        provider.enable_multilingual = False
        provider.final_text_stability_ms = 0
        provider._last_definite_text = ""
        provider._last_definite_at = 0.0
        provider._auto_stop_task = None
        provider._auto_stop_handled = False

        provider.parse_response = lambda response: response

        async def handle_voice_stop(connection, audio):
            connection.voice_stop_calls.append((provider.text, audio))
            connection.stop_event.set()

        provider.handle_voice_stop = handle_voice_stop
        return provider

    @staticmethod
    def _result(text):
        return {
            "payload_msg": {
                "result": {"utterances": [{"definite": True, "text": text}]}
            }
        }

    async def test_auto_mode_waits_for_vad_stop_and_deduplicates_final_text(self):
        conn = _FakeConnection()
        responses = [
            self._result("你"),
            self._result("你"),
            self._result("你好"),
            lambda: (setattr(conn, "client_voice_stop", True) or {"payload_msg": {}}),
            self._result("你好"),
        ]
        provider = self._provider(responses, conn)

        await provider._forward_asr_results(conn)

        self.assertEqual(1, len(conn.voice_stop_calls))
        self.assertEqual("你好", conn.voice_stop_calls[0][0])

    async def test_auto_mode_does_not_stop_without_vad_stop(self):
        conn = _FakeConnection()

        def finish_without_vad_stop():
            conn.stop_event.set()

        provider = self._provider(
            [self._result("你好"), finish_without_vad_stop], conn
        )

        await provider._forward_asr_results(conn)

        self.assertEqual([], conn.voice_stop_calls)

    async def test_auto_mode_does_not_submit_empty_result_before_vad_stop(self):
        conn = _FakeConnection()

        def finish_without_vad_stop():
            conn.stop_event.set()
            return {"payload_msg": {}}

        empty_result = {
            "payload_msg": {
                "audio_info": {"duration": 2500},
                "result": {"utterances": [], "text": ""},
            }
        }
        provider = self._provider(
            [empty_result, finish_without_vad_stop], conn
        )

        await provider._forward_asr_results(conn)

        self.assertEqual([], conn.voice_stop_calls)

    async def test_empty_result_after_vad_stop_submits_previous_stable_text(self):
        conn = _FakeConnection()
        empty_result = {
            "payload_msg": {
                "audio_info": {"duration": 2500},
                "result": {"utterances": [], "text": ""},
            }
        }
        provider = self._provider(
            [
                self._result("你好"),
                lambda: (setattr(conn, "client_voice_stop", True) or empty_result),
            ],
            conn,
        )

        await provider._forward_asr_results(conn)

        self.assertEqual(1, len(conn.voice_stop_calls))
        self.assertEqual("你好", conn.voice_stop_calls[0][0])

    async def test_auto_mode_waits_until_final_text_is_stable(self):
        conn = _FakeConnection()
        conn.client_voice_stop = True

        async def keep_socket_open_for_stability_window():
            await asyncio.sleep(0.05)
            return {"payload_msg": {}}

        provider = self._provider(
            [self._result("你好"), keep_socket_open_for_stability_window], conn
        )
        provider.final_text_stability_ms = 30

        forward_task = asyncio.create_task(provider._forward_asr_results(conn))
        await asyncio.sleep(0.01)
        self.assertEqual([], conn.voice_stop_calls)

        await forward_task

        self.assertEqual(1, len(conn.voice_stop_calls))
        self.assertEqual("你好", conn.voice_stop_calls[0][0])

    async def test_auto_mode_keeps_partial_text_when_barge_in_ends_quickly(self):
        conn = _FakeConnection()
        conn.client_voice_stop = True
        partial_result = {
            "payload_msg": {
                "result": {
                    "text": "今天天气怎么样",
                    "utterances": [
                        {"definite": False, "text": "今天天气怎么样"}
                    ],
                }
            }
        }

        def finish_after_partial_result():
            conn.stop_event.set()
            return {"payload_msg": {}}

        provider = self._provider(
            [partial_result, finish_after_partial_result], conn
        )

        await provider._forward_asr_results(conn)

        self.assertEqual(1, len(conn.voice_stop_calls))
        self.assertEqual("今天天气怎么样", conn.voice_stop_calls[0][0])

    async def test_manual_mode_still_waits_for_stop_signal(self):
        conn = _FakeConnection()
        conn.client_listen_mode = "manual"
        responses = [
            self._result("你"),
            lambda: (
                setattr(conn, "client_voice_stop", True) or self._result("好")
            ),
        ]
        provider = self._provider(responses, conn)

        await provider._forward_asr_results(conn)

        self.assertEqual(1, len(conn.voice_stop_calls))
        self.assertEqual("你好", conn.voice_stop_calls[0][0])


if __name__ == "__main__":
    unittest.main()
