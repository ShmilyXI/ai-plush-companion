import asyncio
import queue
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.providers.tts.huoshan_double_stream import (
    AUDIO_ONLY_RESPONSE,
    EVENT_SessionFinished,
    Header,
    Optional,
    Response,
    TTSProvider,
)


def make_provider(**config_overrides):
    config = {
        "appid": "app-id",
        "access_token": "access-token",
        "resource_id": "seed-tts-1.0",
        "ws_url": "wss://example.test/tts",
        "speaker": "voice-code",
        "phrase_buffer_first_chars": 6,
        "phrase_buffer_chars": 12,
        **config_overrides,
    }
    provider = TTSProvider(config, True)
    provider.ws = object()
    provider.conn = SimpleNamespace(sentence_id="sentence-a")
    provider.send_text = AsyncMock()
    return provider


def test_tiny_chunks_are_combined_until_punctuation():
    provider = make_provider()

    async def exercise():
        for chunk in ("你", "好", "呀"):
            await provider.text_to_speak(chunk, None)

        provider.send_text.assert_not_awaited()

        await provider.text_to_speak("。", None)

    asyncio.run(exercise())

    provider.send_text.assert_awaited_once_with(
        "voice-code", "你好呀。", "sentence-a"
    )


def test_first_and_later_phrases_use_different_thresholds():
    provider = make_provider(
        phrase_buffer_first_chars=3,
        phrase_buffer_chars=5,
    )

    async def exercise():
        await provider.text_to_speak("你好呀", None)
        await provider.text_to_speak("欢迎", None)

        assert [call.args[1] for call in provider.send_text.await_args_list] == ["你好呀"]

        await provider.text_to_speak("回来吧", None)

    asyncio.run(exercise())

    assert [call.args[1] for call in provider.send_text.await_args_list] == [
        "你好呀",
        "欢迎回来吧",
    ]


def test_flush_sends_remainder_and_pending_replacement_prefix():
    provider = make_provider(correct_words=["AI|人工智能"])

    async def exercise():
        await provider.text_to_speak("喜欢A", None)
        provider.send_text.assert_not_awaited()

        await provider.flush_text_buffer()

    asyncio.run(exercise())

    provider.send_text.assert_awaited_once_with(
        "voice-code", "喜欢A", "sentence-a"
    )


def test_send_error_clears_phrase_buffer():
    provider = make_provider(phrase_buffer_first_chars=2)
    provider.send_text.side_effect = RuntimeError("send failed")

    async def exercise():
        with pytest.raises(RuntimeError, match="send failed"):
            await provider.text_to_speak("你好", None)

        provider.send_text.reset_mock(side_effect=True)
        provider.ws = object()
        await provider.text_to_speak("。", None)

    asyncio.run(exercise())

    provider.send_text.assert_awaited_once_with("voice-code", "。", "sentence-a")


def test_missing_websocket_clears_phrase_buffer():
    provider = make_provider()
    provider._phrase_buffer = "无法发送的残留"
    provider.ws = None

    asyncio.run(provider.text_to_speak("新内容", None))

    assert provider._phrase_buffer == ""


def test_last_flushes_remainder_before_finishing_session():
    provider = make_provider()
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        sentence_id="sentence-a",
        client_abort=False,
        stop_event=stop_event,
        loop=object(),
    )
    provider.tts_text_queue = queue.Queue()
    provider._handle_tts_lifecycle_message = MagicMock()
    provider._complete_tts_debug = MagicMock()
    provider._emit_tts_failed = MagicMock()
    calls = []

    async def flush_text_buffer():
        calls.append("flush")

    provider.flush_text_buffer = AsyncMock(side_effect=flush_text_buffer)

    async def finish_session(_sentence_id):
        calls.append("finish")
        stop_event.set()

    provider.finish_session = AsyncMock(side_effect=finish_session)
    provider.tts_text_queue.put(
        TTSMessageDTO("sentence-a", SentenceType.LAST, ContentType.ACTION)
    )

    def run_immediately(coro, loop):
        del loop
        result = asyncio.run(coro)
        future = MagicMock()
        future.result.return_value = result
        return future

    with patch(
        "core.providers.tts.huoshan_double_stream.asyncio.run_coroutine_threadsafe",
        side_effect=run_immediately,
    ):
        provider.tts_text_priority_thread()

    provider.flush_text_buffer.assert_awaited_once()
    provider.finish_session.assert_awaited_once_with("sentence-a")
    provider._complete_tts_debug.assert_not_called()
    assert calls == ["flush", "finish"]


def test_first_tracks_sentence_for_audio_and_stop_messages():
    provider = make_provider()
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        sentence_id="sentence-a",
        client_abort=False,
        stop_event=stop_event,
        loop=object(),
    )
    provider.tts_text_queue = queue.Queue()
    provider._handle_tts_lifecycle_message = MagicMock()
    provider.before_stop_play_files = []

    async def start_session(_sentence_id):
        stop_event.set()

    provider.start_session = AsyncMock(side_effect=start_session)
    provider.tts_text_queue.put(
        TTSMessageDTO("sentence-a", SentenceType.FIRST, ContentType.ACTION)
    )

    def run_immediately(coro, loop):
        del loop
        result = asyncio.run(coro)
        future = MagicMock()
        future.result.return_value = result
        return future

    with patch(
        "core.providers.tts.huoshan_double_stream.asyncio.run_coroutine_threadsafe",
        side_effect=run_immediately,
    ):
        provider.tts_text_priority_thread()

    assert provider.current_sentence_id == "sentence-a"


def test_session_finished_without_audio_fails_and_still_queues_stop():
    provider = make_provider(resource_id="seed-tts-2.0")
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        sentence_id="sentence-a",
        stop_event=stop_event,
    )
    provider.current_sentence_id = "sentence-a"
    provider.activate_session = True
    provider._session_audio_received = False
    provider._emit_tts_failed = MagicMock()
    provider.print_response = MagicMock()

    response = Response(
        Header(message_type=AUDIO_ONLY_RESPONSE),
        Optional(event=EVENT_SessionFinished, sessionId="sentence-a"),
    )
    provider.parser_response = MagicMock(return_value=response)

    class FakeWebSocket:
        async def recv(self):
            stop_event.set()
            return b"response"

        async def close(self):
            return None

    provider.ws = FakeWebSocket()

    asyncio.run(provider._start_monitor_tts_response())

    provider._emit_tts_failed.assert_called_once()
    sentence_type, audio, text, sentence_id = provider.tts_audio_queue.get_nowait()
    assert sentence_type == SentenceType.LAST
    assert audio == []
    assert text is None
    assert sentence_id == "sentence-a"


def test_session_finish_timeout_fails_and_queues_stop():
    provider = make_provider(tts_session_finish_timeout=0)
    provider.conn = SimpleNamespace(sentence_id="sentence-a")
    provider.current_sentence_id = "sentence-a"
    provider.activate_session = True
    provider._session_stop_enqueued = False
    provider._emit_tts_failed = MagicMock()

    asyncio.run(provider._guard_session_completion("sentence-a"))

    provider._emit_tts_failed.assert_called_once()
    sentence_type, audio, text, sentence_id = provider.tts_audio_queue.get_nowait()
    assert sentence_type == SentenceType.LAST
    assert audio == []
    assert text is None
    assert sentence_id == "sentence-a"


def test_connection_start_event_is_sent_before_first_session():
    provider = make_provider()
    provider.ws = None
    provider._cancel_monitor_task = AsyncMock()
    provider._start_monitor_tts_response = AsyncMock()
    provider.start_connection = AsyncMock()
    provider.enable_ws_reuse = True

    async def exercise():
        connection = AsyncMock()
        with patch(
            "core.providers.tts.huoshan_double_stream.websockets.connect",
            new_callable=AsyncMock,
            return_value=connection,
        ):
            await provider._ensure_connection()

    asyncio.run(exercise())
    provider.start_connection.assert_awaited_once()


def test_abort_clears_phrase_buffer():
    provider = make_provider()
    provider._phrase_buffer = "不要带到下一轮"
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        sentence_id="sentence-a",
        client_abort=True,
        stop_event=stop_event,
        loop=object(),
    )
    provider.tts_text_queue = queue.Queue()
    provider._cancel_tts_debug = MagicMock()
    provider._emit_tts_failed = MagicMock()

    async def cancel_session(_sentence_id):
        stop_event.set()

    provider.cancel_session = AsyncMock(side_effect=cancel_session)
    provider.tts_text_queue.put(
        TTSMessageDTO("sentence-a", SentenceType.MIDDLE, ContentType.TEXT, "新内容")
    )

    def run_immediately(coro, loop):
        del loop
        result = asyncio.run(coro)
        future = MagicMock()
        future.result.return_value = result
        return future

    with patch(
        "core.providers.tts.huoshan_double_stream.asyncio.run_coroutine_threadsafe",
        side_effect=run_immediately,
    ):
        provider.tts_text_priority_thread()

    assert provider._phrase_buffer == ""


def test_stale_message_clears_phrase_buffer():
    provider = make_provider()
    provider._phrase_buffer = "旧会话残留"
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        sentence_id="sentence-current",
        client_abort=False,
        stop_event=stop_event,
        loop=object(),
    )

    class OneMessageQueue:
        def get(self, timeout):
            del timeout
            stop_event.set()
            return TTSMessageDTO(
                "sentence-old", SentenceType.MIDDLE, ContentType.TEXT, "旧内容"
            )

    provider.tts_text_queue = OneMessageQueue()
    provider._cancel_tts_debug = MagicMock()

    provider.tts_text_priority_thread()

    assert provider._phrase_buffer == ""


def test_reset_stream_state_clears_phrase_buffer():
    provider = make_provider()
    provider._phrase_buffer = "上一轮残留"
    provider._phrase_buffer_is_first = False
    provider._pending_prefix = "A"

    provider.reset_stream_state()

    assert provider._phrase_buffer == ""
    assert provider._phrase_buffer_is_first is True
    assert provider._pending_prefix == ""
