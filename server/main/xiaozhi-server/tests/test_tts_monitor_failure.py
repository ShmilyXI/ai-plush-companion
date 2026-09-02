import asyncio
import importlib
import threading
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from core.handle.sendAudioHandle import send_tts_message
from core.providers.tts.base import TTSProviderBase


class FakeTts(TTSProviderBase):
    async def text_to_speak(self, text, output_file):
        return None


def test_monitor_failure_routes_to_proactive_terminal_and_cancel():
    terminal = Mock()
    cancel = Mock()
    conn = SimpleNamespace(
        sentence_id="sentence-1",
        proactive_playback_active=True,
        _on_proactive_tts_terminal=terminal,
        cancel_proactive_playback=cancel,
        config={},
    )
    provider = FakeTts({}, True)
    provider.conn = conn

    assert provider._handle_monitor_failure("sentence-1", RuntimeError("upstream")) is True

    terminal.assert_called_once()
    assert terminal.call_args.args[0] == "sentence-1"
    assert terminal.call_args.args[1] is False
    cancel.assert_called_once()


def test_monitor_failure_resets_ordinary_connection_playback_state():
    clear_status = Mock()
    rate_controller = Mock()
    conn = SimpleNamespace(
        sentence_id="sentence-1",
        proactive_playback_active=False,
        client_is_speaking=True,
        client_abort=False,
        clearSpeakStatus=clear_status,
        audio_rate_controller=rate_controller,
        config={},
    )
    provider = FakeTts({}, True)
    provider.conn = conn
    provider.tts_text_queue.put("stale-text")
    provider.tts_audio_queue.put("stale-audio")

    assert provider._handle_monitor_failure("sentence-1", RuntimeError("upstream")) is True

    assert conn.client_abort is True
    clear_status.assert_called_once()
    rate_controller.stop_sending.assert_called_once()
    rate_controller.reset.assert_called_once()
    assert provider.tts_text_queue.empty()
    assert provider.tts_audio_queue.empty()


def test_tts_generation_completion_does_not_mark_proactive_playback_complete():
    terminal = Mock()
    conn = SimpleNamespace(
        emit_debug_event=Mock(return_value=True),
        _on_proactive_tts_terminal=terminal,
        config={},
    )
    provider = FakeTts({}, True)
    provider.conn = conn
    provider._debug_tts_started_at["sentence-1"] = time.monotonic()

    assert provider._complete_tts_debug("sentence-1") is True

    terminal.assert_not_called()


def test_duplicate_tts_completion_without_start_is_ignored():
    terminal = Mock()
    conn = SimpleNamespace(
        emit_debug_event=Mock(return_value=True),
        _on_proactive_tts_terminal=terminal,
        config={},
    )
    provider = FakeTts({}, True)
    provider.conn = conn

    assert provider._complete_tts_debug("sentence-missing") is False

    terminal.assert_not_called()


@pytest.mark.asyncio
async def test_stop_without_active_proactive_playback_does_not_emit_success_terminal():
    terminal = Mock()
    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id=None,
        proactive_playback_active=False,
        config={},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=AsyncMock()),
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=terminal,
    )

    await send_tts_message(conn, "stop")

    terminal.assert_not_called()


class _MalformedWebSocket:
    def __init__(self):
        self._first = True
        self.closed = False

    async def recv(self):
        if self._first:
            self._first = False
            return "not-json"
        await asyncio.sleep(10)
        return ""

    async def close(self):
        self.closed = True


@pytest.mark.asyncio
@pytest.mark.parametrize("module_name", ["aliyun_stream", "xunfei_stream", "alibl_stream"])
async def test_malformed_monitor_frame_fails_the_active_tts_session(module_name):
    module = importlib.import_module(f"core.providers.tts.{module_name}")
    provider = object.__new__(module.TTSProvider)
    provider.conn = SimpleNamespace(
        stop_event=threading.Event(),
        client_abort=False,
        sentence_id="sentence-1",
    )
    provider.ws = _MalformedWebSocket()
    provider._monitor_task = None
    provider.activate_session = True
    provider.last_active_time = 0
    provider.task_id = "sentence-1"
    provider._pending_prefix = ""
    provider._handle_monitor_failure = Mock()

    await asyncio.wait_for(provider._start_monitor_tts_response(), timeout=0.5)

    provider._handle_monitor_failure.assert_called_once()
    assert provider._handle_monitor_failure.call_args.args[0] == "sentence-1"


@pytest.mark.asyncio
async def test_late_aliyun_error_frame_does_not_fail_the_new_sentence():
    module = importlib.import_module("core.providers.tts.aliyun_stream")
    stop_event = threading.Event()

    class LateFrameWebSocket:
        async def recv(self):
            stop_event.set()
            return '{"header":{"task_id":"old-task","code":500,"name":"TaskFailed","message":"old"}}'

        async def close(self):
            return None

    provider = object.__new__(module.TTSProvider)
    provider.conn = SimpleNamespace(
        stop_event=stop_event,
        client_abort=False,
        sentence_id="new-sentence",
    )
    provider.ws = LateFrameWebSocket()
    provider._monitor_task = None
    provider.activate_session = True
    provider.last_active_time = 0
    provider.task_id = "new-task"
    provider._pending_prefix = ""
    provider._handle_monitor_failure = Mock()

    await provider._start_monitor_tts_response()

    provider._handle_monitor_failure.assert_not_called()


@pytest.mark.asyncio
async def test_huoshan_session_failed_ends_monitor_and_fails_active_sentence():
    module = importlib.import_module("core.providers.tts.huoshan_double_stream")

    class FailedWebSocket:
        def __init__(self):
            self.closed = False

        async def recv(self):
            return b"failure-frame"

        async def close(self):
            self.closed = True

    provider = object.__new__(module.TTSProvider)
    provider.conn = SimpleNamespace(
        stop_event=threading.Event(),
        sentence_id="sentence-1",
    )
    provider.ws = FailedWebSocket()
    provider._monitor_task = None
    provider.activate_session = True
    provider._session_finish_watchdog = None
    provider._session_stop_enqueued = False
    provider.current_sentence_id = "sentence-1"
    provider.parser_response = lambda _message: SimpleNamespace(
        optional=SimpleNamespace(
            event=module.EVENT_SessionFailed,
            sessionId="sentence-1",
            response_meta_json="upstream failure",
        ),
        header=SimpleNamespace(message_type=0),
        payload=b"",
    )
    provider.print_response = Mock()
    provider._handle_monitor_failure = Mock()
    provider._enqueue_session_stop = Mock()

    await asyncio.wait_for(provider._start_monitor_tts_response(), timeout=0.5)

    provider._handle_monitor_failure.assert_called_once()
    assert provider._handle_monitor_failure.call_args.args[0] == "sentence-1"
    provider._enqueue_session_stop.assert_called_once_with("sentence-1")
    assert provider.ws is None


@pytest.mark.asyncio
async def test_proactive_success_terminal_waits_for_audio_queue_and_stop_send():
    terminal = Mock()
    events = []

    class PendingRateController:
        frame_duration = 0

        def __init__(self):
            self.queue = []
            self.queue_empty_event = asyncio.Event()

        def stop_sending(self):
            events.append("rate-stop")

    async def send(_message):
        events.append("stop-send")

    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        proactive_playback_active=True,
        _proactive_audio_started=True,
        config={},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=send),
        audio_rate_controller=PendingRateController(),
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=lambda *args: (events.append("terminal"), terminal(*args)),
        logger=Mock(),
    )

    task = asyncio.create_task(send_tts_message(conn, "stop"))
    await asyncio.sleep(0)
    terminal.assert_not_called()

    conn.audio_rate_controller.queue_empty_event.set()
    await task

    terminal.assert_called_once_with("sentence-1", True, None)
    assert events.index("stop-send") < events.index("terminal")


@pytest.mark.asyncio
async def test_proactive_stop_without_audio_is_a_failed_terminal():
    terminal = Mock()

    async def send(_message):
        return None

    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        _proactive_audio_started=False,
        proactive_playback_active=True,
        config={},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=send),
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=terminal,
        logger=Mock(),
    )

    await send_tts_message(conn, "stop")

    terminal.assert_called_once()
    assert terminal.call_args.args[0] == "sentence-1"
    assert terminal.call_args.args[1] is False


@pytest.mark.asyncio
async def test_stop_notification_does_not_count_as_proactive_audio(monkeypatch):
    terminal = Mock()
    sent = []

    async def send(payload):
        sent.append(payload)

    async def fake_audio_to_data(_path, is_opus=True):
        return [b"notification-opus"]

    async def no_wait(_conn):
        return None

    monkeypatch.setattr("core.handle.sendAudioHandle.audio_to_data", fake_audio_to_data)
    monkeypatch.setattr("core.handle.sendAudioHandle._wait_for_audio_completion", no_wait)
    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        _proactive_audio_started=False,
        proactive_playback_active=True,
        client_abort=False,
        conn_from_mqtt_gateway=False,
        client_aec=False,
        config={"enable_stop_tts_notify": True, "tts_audio_send_delay": 0},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=send),
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=terminal,
        logger=Mock(),
    )

    await send_tts_message(conn, "stop")

    assert conn._proactive_audio_started is False
    terminal.assert_called_once()
    assert terminal.call_args.args[1] is False
    assert any(payload == b"notification-opus" for payload in sent)


@pytest.mark.asyncio
async def test_stop_notification_failure_resolves_proactive_terminal(monkeypatch):
    terminal = Mock()

    async def fail_audio_to_data(_path, is_opus=True):
        raise RuntimeError("notification unavailable")

    monkeypatch.setattr("core.handle.sendAudioHandle.audio_to_data", fail_audio_to_data)
    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        _proactive_audio_started=False,
        proactive_playback_active=True,
        config={"enable_stop_tts_notify": True},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=AsyncMock()),
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=terminal,
        logger=Mock(),
    )

    with pytest.raises(RuntimeError, match="notification unavailable"):
        await send_tts_message(conn, "stop")

    terminal.assert_called_once()
    assert terminal.call_args.args[0] == "sentence-1"
    assert terminal.call_args.args[1] is False


@pytest.mark.asyncio
async def test_stop_notification_preserves_prior_background_send_failure(monkeypatch):
    terminal = Mock()

    async def send(_payload):
        return None

    async def fake_audio_to_data(_path, is_opus=True):
        return [b"notification-opus"]

    async def no_wait(_conn):
        return None

    monkeypatch.setattr("core.handle.sendAudioHandle.audio_to_data", fake_audio_to_data)
    monkeypatch.setattr("core.handle.sendAudioHandle._wait_for_audio_completion", no_wait)

    from core.utils.audioRateController import AudioRateController

    controller = AudioRateController(frame_duration=0)
    controller.send_error = RuntimeError("background socket closed")
    controller.pending_send_task = asyncio.create_task(asyncio.sleep(0))
    await controller.pending_send_task
    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        _proactive_audio_started=True,
        proactive_playback_active=True,
        client_abort=False,
        conn_from_mqtt_gateway=False,
        client_aec=False,
        config={"enable_stop_tts_notify": True, "tts_audio_send_delay": 0},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=send),
        audio_rate_controller=controller,
        audio_flow_control={"sentence_id": "sentence-1"},
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=terminal,
        logger=Mock(),
    )

    with pytest.raises(RuntimeError, match="background socket closed"):
        await send_tts_message(conn, "stop")

    terminal.assert_called_once()
    assert terminal.call_args.args[1] is False


@pytest.mark.asyncio
async def test_failed_audio_send_does_not_count_as_proactive_audio():
    terminal = Mock()

    async def send(_packet):
        raise RuntimeError("socket closed")

    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        _proactive_audio_started=False,
        proactive_playback_active=True,
        client_abort=False,
        conn_from_mqtt_gateway=False,
        config={"tts_audio_send_delay": 0},
        websocket=SimpleNamespace(send=send),
        logger=Mock(),
    )
    from core.handle.sendAudioHandle import sendAudio

    with pytest.raises(RuntimeError):
        await sendAudio(conn, b"opus")

    assert conn._proactive_audio_started is False


@pytest.mark.asyncio
async def test_stop_times_out_when_audio_sender_does_not_signal_completion():
    terminal = Mock()

    class StuckRateController:
        frame_duration = 60

        def __init__(self):
            self.queue = ["pending"]
            self.queue_empty_event = asyncio.Event()

        def stop_sending(self):
            return None

    async def send(_message):
        return None

    conn = SimpleNamespace(
        session_id="session-1",
        sentence_id="sentence-1",
        _proactive_sentence_id="sentence-1",
        _proactive_audio_started=True,
        proactive_playback_active=True,
        config={"tts_audio_completion_timeout_seconds": 0.01},
        calling=False,
        close_after_chat=False,
        websocket=SimpleNamespace(send=send),
        audio_rate_controller=StuckRateController(),
        clearSpeakStatus=Mock(),
        _on_proactive_tts_terminal=terminal,
        logger=Mock(),
    )

    with pytest.raises(RuntimeError, match="audio completion timeout"):
        await send_tts_message(conn, "stop")

    terminal.assert_called_once()
    assert terminal.call_args.args[1] is False
