import asyncio
import ast
import importlib
import json
import queue
import threading
import types
import sys
from collections import deque
from types import SimpleNamespace

import numpy as np
import pytest
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module

from core.debug_events import DebugEventReporter
from core.handle.textHandler.pingMessageHandler import PingMessageHandler
from core.providers.asr.base import ASRProviderBase
from core.providers.tts.base import TTSProviderBase
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.providers.tools.unified_tool_manager import ToolManager
from plugins_func.register import Action, ActionResponse


tool_handler_module = types.ModuleType("core.providers.tools.unified_tool_handler")
tool_handler_module.UnifiedToolHandler = object
sys.modules["core.providers.tools.unified_tool_handler"] = tool_handler_module
plugin_loader_module = types.ModuleType("plugins_func.loadplugins")
plugin_loader_module.auto_import_modules = lambda *args, **kwargs: None
sys.modules["plugins_func.loadplugins"] = plugin_loader_module


class CapturingReporter:
    def __init__(self):
        self.events = []
        self.closed = False

    def emit(self, category, event_type, level, summary, **kwargs):
        self.events.append(
            {
                "category": category,
                "eventType": event_type,
                "level": level,
                "summary": summary,
                "details": kwargs.get("details") or {},
                "sentenceId": kwargs.get("sentence_id"),
                "durationMs": kwargs.get("duration_ms"),
            }
        )
        return True

    def close(self):
        self.closed = True


def event_types(reporter):
    return [event["eventType"] for event in reporter.events]


def assert_no_sensitive_runtime_content(events):
    serialized = json.dumps(events, ensure_ascii=False).lower()
    for forbidden in (
        "thinking",
        "reasoning",
        "systemprompt",
        '"messages"',
        "raw audio",
        "pcm-secret",
        "wav-secret",
        "opus-secret",
        "/tmp/secret",
    ):
        assert forbidden not in serialized


def make_connection(**config_overrides):
    from core.connection import ConnectionHandler

    config = {
        "exit_commands": ["退出"],
        "close_connection_no_voice_time": 120,
        "selected_module": {"LLM": "fake-llm"},
        "xiaozhi": {"audio_params": {"sample_rate": 24000}},
    }
    config.update(config_overrides)
    return ConnectionHandler(config, None, None, None, None, None)


def test_emit_debug_event_isolated_from_reporter_errors():
    connection = make_connection()

    class BrokenReporter:
        def emit(self, *args, **kwargs):
            raise RuntimeError("secret reporter failure")

    connection.debug_events = BrokenReporter()
    try:
        assert connection.emit_debug_event("device", "connection.opened", "info", "连接已建立") is False
    finally:
        connection.executor.shutdown(wait=False)


def test_connection_open_close_and_failure_events(monkeypatch):
    created = []

    def reporter_factory(device_id, session_id):
        reporter = CapturingReporter()
        created.append((device_id, session_id, reporter))
        return reporter

    async def scenario():
        monkeypatch.setattr("core.connection.DebugEventReporter", reporter_factory)
        connection = make_connection()

        class WebSocket:
            request = SimpleNamespace(headers={"device-id": "device-a"}, path="/")
            remote_address = ("127.0.0.1", 1234)

            def __aiter__(self):
                async def messages():
                    if False:
                        yield None
                return messages()

            async def close(self):
                return None

        connection._background_initialize = lambda: asyncio.sleep(0)
        connection._check_timeout = lambda: asyncio.sleep(0)
        connection._check_aec_cache_expiry = lambda: asyncio.sleep(0)
        connection._save_and_close = lambda ws: connection.close(ws)
        await connection.handle_connection(WebSocket())

        device_id, session_id, reporter = created[0]
        assert (device_id, session_id) == ("device-a", connection.session_id)
        assert event_types(reporter) == ["connection.opened", "connection.closed"]
        assert reporter.closed

        failed = make_connection()

        class FailingWebSocket(WebSocket):
            def __aiter__(self):
                async def messages():
                    raise RuntimeError("token=secret headers=config")
                    yield None
                return messages()

        failed._background_initialize = lambda: asyncio.sleep(0)
        failed._check_timeout = lambda: asyncio.sleep(0)
        failed._check_aec_cache_expiry = lambda: asyncio.sleep(0)
        failed._save_and_close = lambda ws: asyncio.sleep(0)
        await failed.handle_connection(FailingWebSocket())
        failed_reporter = created[-1][2]
        assert event_types(failed_reporter) == ["connection.opened", "connection.failed"]
        failed_event = failed_reporter.events[-1]
        assert failed_event["details"]["errorClass"] == "RuntimeError"
        assert "token" not in failed_event["details"]["message"].lower()
        failed.executor.shutdown(wait=False)

    asyncio.run(scenario())


def test_heartbeat_sampled_at_most_once_per_minute(monkeypatch):
    now = iter([10.0, 20.0, 70.0])
    fake_time = SimpleNamespace(
        monotonic=lambda: next(now),
        time=lambda: 0.0,
        localtime=lambda: (),
        strftime=lambda *args: "now",
    )
    monkeypatch.setattr("core.handle.textHandler.pingMessageHandler.time", fake_time)
    reporter = CapturingReporter()
    conn = SimpleNamespace(
        config={"enable_websocket_ping": True},
        logger=SimpleNamespace(debug=lambda *a, **k: None, error=lambda *a, **k: None),
        websocket=SimpleNamespace(send=lambda payload: asyncio.sleep(0)),
        last_activity_time=0,
        _last_debug_heartbeat_at=0.0,
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs),
    )
    handler = PingMessageHandler()
    asyncio.run(handler.handle(conn, {"type": "ping", "token": "secret"}))
    asyncio.run(handler.handle(conn, {"type": "ping", "token": "secret"}))
    asyncio.run(handler.handle(conn, {"type": "ping", "token": "secret"}))
    assert event_types(reporter) == ["heartbeat.sampled", "heartbeat.sampled"]
    assert reporter.events[0]["details"] == {}


def test_vad_emits_only_state_transitions():
    sys.modules.setdefault(
        "onnxruntime",
        SimpleNamespace(
            SessionOptions=object,
            InferenceSession=lambda *args, **kwargs: None,
        ),
    )
    from core.providers.vad.silero import VADProvider

    provider = object.__new__(VADProvider)
    provider.vad_threshold = 0.5
    provider.vad_threshold_low = 0.2
    provider.silence_threshold_ms = 1000
    provider.frame_window_threshold = 1
    probabilities = iter([0.8, 0.9, 0.1, 0.1])
    provider.session = SimpleNamespace(run=lambda *args: (np.array([next(probabilities)]), np.zeros((2, 1, 128), dtype=np.float32)))
    reporter = CapturingReporter()
    conn = SimpleNamespace(
        client_listen_mode="auto",
        client_audio_buffer=bytearray(),
        client_voice_window=deque(maxlen=5),
        client_have_voice=False,
        client_voice_stop=False,
        vad_last_voice_time=0.0,
        last_is_voice=False,
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs),
    )
    for _ in range(4):
        provider.is_vad(conn, bytes(1024))
    assert event_types(reporter) == ["vad.voice_started", "vad.voice_stopped"]


class FakeAsrProvider(ASRProviderBase):
    def __init__(self, result=None, error=None):
        super().__init__()
        self.result = result
        self.error = error

    async def speech_to_text(self, opus_data, session_id, artifacts=None):
        if self.error:
            raise self.error
        return self.result, "/tmp/secret.wav"


def test_asr_success_failure_and_final_user_text(monkeypatch, tmp_path):
    reporter = CapturingReporter()
    started_chat = []
    monkeypatch.setattr("core.providers.asr.base.enqueue_asr_report", lambda *args, **kwargs: None)
    monkeypatch.setattr("core.providers.asr.base.startToChat", lambda conn, text: _capture_async(started_chat, text))
    monkeypatch.setattr("core.providers.asr.base.shutil.disk_usage", lambda path: SimpleNamespace(free=10**9))
    provider = FakeAsrProvider({"content": "你好", "language": "zh", "emotion": "happy"})
    provider.output_dir = str(tmp_path)
    provider.delete_audio_file = True
    conn = SimpleNamespace(
        session_id="session-a",
        voiceprint_provider=None,
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs),
    )
    asyncio.run(provider.handle_voice_stop(conn, [b"pcm-secret"]))
    assert started_chat == ['{"content": "你好", "language": "zh", "emotion": "happy"}']
    assert event_types(reporter) == ["asr.started", "asr.completed", "conversation.user"]
    assert reporter.events[1]["details"] == {
        "language": "zh",
        "emotion": "happy",
        "speaker": None,
        "textLength": 2,
    }
    assert reporter.events[2]["details"]["text"] == started_chat[0]
    assert reporter.events[2]["details"]["transcript"] == "你好"

    speaker_reporter = CapturingReporter()
    speaker_chat = []
    monkeypatch.setattr(
        "core.providers.asr.base.startToChat",
        lambda conn, text: _capture_async(speaker_chat, text),
    )
    speaker_provider = FakeAsrProvider("你好")
    speaker_provider.output_dir = str(tmp_path)
    speaker_provider.delete_audio_file = True
    conn.voiceprint_provider = SimpleNamespace(
        identify_speaker=lambda wav, session: _return_async("小夏")
    )
    conn.emit_debug_event = lambda *args, **kwargs: speaker_reporter.emit(
        *args, **kwargs
    )
    asyncio.run(speaker_provider.handle_voice_stop(conn, [b"pcm-secret"]))
    speaker_user = speaker_reporter.events[-1]
    assert speaker_user["eventType"] == "conversation.user"
    assert speaker_user["details"]["text"] == speaker_chat[0]
    assert json.loads(speaker_user["details"]["text"]) == {
        "speaker": "小夏",
        "content": "你好",
    }
    assert speaker_user["details"]["transcript"] == "你好"
    assert speaker_user["details"]["speaker"] == "小夏"

    failed_reporter = CapturingReporter()
    failed = FakeAsrProvider(error=RuntimeError("/tmp/secret.wav pcm-secret"))
    failed.output_dir = str(tmp_path)
    failed.delete_audio_file = True
    conn.emit_debug_event = lambda *args, **kwargs: failed_reporter.emit(*args, **kwargs)
    asyncio.run(failed.handle_voice_stop(conn, [b"pcm-secret"]))
    assert event_types(failed_reporter) == ["asr.started", "asr.failed"]
    assert failed_reporter.events[-1]["details"] == {"errorClass": "RuntimeError"}
    assert_no_sensitive_runtime_content(reporter.events + failed_reporter.events)


async def _capture_async(target, value):
    target.append(value)


async def _return_async(value):
    return value


class FakeTtsProvider(TTSProviderBase):
    async def text_to_speak(self, text, output_file):
        return b""


def test_tts_started_completed_and_failed(monkeypatch):
    provider = FakeTtsProvider({}, True)
    reporter = CapturingReporter()
    conn = SimpleNamespace(
        stop_event=threading.Event(),
        client_abort=False,
        sentence_id="sentence-a",
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs),
    )
    provider.conn = conn
    provider._handle_tts_lifecycle_message(
        TTSMessageDTO("sentence-a", SentenceType.FIRST, ContentType.ACTION, content_detail="你好")
    )
    provider._handle_tts_lifecycle_message(
        TTSMessageDTO("sentence-a", SentenceType.LAST, ContentType.ACTION)
    )
    assert event_types(reporter) == ["tts.started"]
    provider._complete_tts_debug("sentence-a")
    provider._emit_tts_failed("sentence-b", RuntimeError("/tmp/secret.wav opus-secret"), 1.0)
    assert event_types(reporter) == ["tts.started", "tts.completed", "tts.failed"]
    assert reporter.events[0]["details"] == {"textLength": 2}
    assert reporter.events[-1]["details"] == {"errorClass": "RuntimeError"}
    assert_no_sensitive_runtime_content(reporter.events)


def test_tts_failure_after_last_never_emits_completed():
    provider = FakeTtsProvider({}, True)
    reporter = CapturingReporter()
    provider.conn = SimpleNamespace(
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs)
    )
    provider._handle_tts_lifecycle_message(
        TTSMessageDTO("sentence-a", SentenceType.FIRST, ContentType.ACTION)
    )
    provider._handle_tts_lifecycle_message(
        TTSMessageDTO("sentence-a", SentenceType.LAST, ContentType.ACTION)
    )
    provider._emit_tts_failed("sentence-a", RuntimeError("send failed"))
    provider._complete_tts_debug("sentence-a")
    assert event_types(reporter) == ["tts.started", "tts.failed"]


def test_tts_completed_session_cannot_later_emit_failed():
    provider = FakeTtsProvider({}, True)
    reporter = CapturingReporter()
    provider.conn = SimpleNamespace(
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs)
    )
    provider._handle_tts_lifecycle_message(
        TTSMessageDTO("sentence-a", SentenceType.FIRST, ContentType.ACTION)
    )
    provider._complete_tts_debug("sentence-a")
    provider._emit_tts_failed("sentence-a", RuntimeError("late failure"))
    assert event_types(reporter) == ["tts.started", "tts.completed"]


def test_tool_execution_lifecycle_success_and_failure(monkeypatch):
    reporter = CapturingReporter()
    conn = SimpleNamespace(emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs))
    manager = ToolManager(conn)

    class FakeToolType:
        value = "fake"

    fake_tool_type = FakeToolType()
    manager.get_tool_type = lambda name: fake_tool_type
    manager.executors = {
        fake_tool_type: SimpleNamespace(
            execute=lambda conn, name, arguments: _return_async(
                ActionResponse(Action.RESPONSE, result={"ok": True, "token": "secret"})
            )
        )
    }
    result = asyncio.run(
        manager.execute_tool("weather", {"city": "上海", "token": "secret"})
    )
    assert result.result == {"ok": True, "token": "secret"}
    assert event_types(reporter) == ["tool.called", "tool.completed"]
    assert reporter.events[0]["details"]["arguments"]["token"] == "[redacted]"
    assert "secret" not in reporter.events[1]["details"]["result"]

    async def fail(conn, name, arguments):
        raise RuntimeError("token=secret")

    manager.executors[fake_tool_type].execute = fail
    failed = asyncio.run(manager.execute_tool("weather", {"city": "上海"}))
    assert failed.action == Action.ERROR
    assert event_types(reporter)[-2:] == ["tool.called", "tool.failed"]
    assert reporter.events[-1]["details"] == {"name": "weather", "errorClass": "RuntimeError"}
    assert_no_sensitive_runtime_content(reporter.events)


def test_tool_preview_omits_nested_runtime_secrets_and_large_values():
    dangerous = {
        "ordinary": {
            "thinking": "thought-secret",
            "nested": [
                {"systemPrompt": "prompt-secret"},
                {"payload": b"audio-secret"},
                {"location": "/tmp/private/voice.wav"},
                {"link": "https://example.test/a?token=url-secret"},
            ],
        },
        "long": "x" * 5000,
        "note": "ordinary key contains token=inline-secret",
        "header": "header-secret",
        "saved": "result saved at /tmp/private/result.json",
        "opened": "open /Users/a/config.txt",
    }
    preview = ToolManager._safe_debug_preview(dangerous)
    rendered = repr(preview).lower()
    for secret in (
        "thought-secret",
        "prompt-secret",
        "audio-secret",
        "/tmp/private",
        "url-secret",
        "inline-secret",
        "header-secret",
        "/users/a",
        "result.json",
    ):
        assert secret not in rendered
    assert "[binary omitted]" in rendered
    assert "[path omitted]" in rendered
    assert "[redacted]" in rendered
    assert len(rendered) < 2500


@pytest.mark.parametrize(
    "module_name",
    [
        "index_stream",
        "alibl_stream",
        "xunfei_stream",
        "minimax_httpstream",
        "aliyun_stream",
        "huoshan_double_stream",
    ],
)
def test_streaming_tts_overrides_reuse_base_lifecycle_helpers(module_name):
    source_path = (
        __import__("pathlib").Path(__file__).parents[1]
        / "core"
        / "providers"
        / "tts"
        / f"{module_name}.py"
    )
    tree = ast.parse(source_path.read_text(encoding="utf-8"))
    provider_class = next(
        node
        for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == "TTSProvider"
    )
    method = next(
        node
        for node in provider_class.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == "tts_text_priority_thread"
    )
    calls = {
        node.func.attr
        for node in ast.walk(method)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }
    assert "_handle_tts_lifecycle_message" in calls
    assert "_emit_tts_failed" in calls
    assert "_complete_tts_debug" in calls


def test_index_stream_thread_emits_lifecycle_through_base_helper():
    module = importlib.import_module("core.providers.tts.index_stream")
    provider = object.__new__(module.TTSProvider)
    reporter = CapturingReporter()
    stop_event = threading.Event()

    def emit(*args, **kwargs):
        emitted = reporter.emit(*args, **kwargs)
        if args[1] == "tts.completed":
            stop_event.set()
        return emitted

    provider.conn = SimpleNamespace(stop_event=stop_event, emit_debug_event=emit)
    provider.conn.client_abort = False
    provider.conn.sentence_id = "sentence-a"
    provider.tts_text_queue = queue.Queue()
    provider.tts_audio_queue = queue.Queue()
    provider.before_stop_play_files = []
    provider._debug_tts_started_at = {}
    provider.tts_text_queue.put(
        TTSMessageDTO("sentence-a", SentenceType.FIRST, ContentType.ACTION)
    )
    provider.tts_text_queue.put(
        TTSMessageDTO("sentence-a", SentenceType.LAST, ContentType.ACTION)
    )

    provider.tts_text_priority_thread()

    assert event_types(reporter) == ["tts.started", "tts.completed"]


@pytest.mark.parametrize(
    "module_name",
    [
        "index_stream",
        "alibl_stream",
        "xunfei_stream",
        "minimax_httpstream",
        "aliyun_stream",
        "huoshan_double_stream",
    ],
)
def test_streaming_tts_rejects_messages_before_starting_lifecycle(module_name):
    source_path = (
        __import__("pathlib").Path(__file__).parents[1]
        / "core"
        / "providers"
        / "tts"
        / f"{module_name}.py"
    )
    source = source_path.read_text(encoding="utf-8")
    handle_pos = source.index("self._handle_tts_lifecycle_message(message)")
    abort_pos = source.index("if self.conn.client_abort")
    stale_pos = source.index("if message.sentence_id != self.conn.sentence_id")
    assert abort_pos < handle_pos
    assert stale_pos < handle_pos


def test_index_stream_stale_first_does_not_emit_started():
    module = importlib.import_module("core.providers.tts.index_stream")
    provider = object.__new__(module.TTSProvider)
    reporter = CapturingReporter()
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        stop_event=stop_event,
        client_abort=False,
        sentence_id="current",
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs),
    )
    provider.tts_text_queue = queue.Queue()
    provider._debug_tts_started_at = {}
    provider.tts_text_queue.put(
        TTSMessageDTO("stale", SentenceType.FIRST, ContentType.ACTION)
    )
    provider.tts_text_queue.put(SimpleNamespace(sentence_id="stop"))

    original_get = provider.tts_text_queue.get

    def get_once(*args, **kwargs):
        message = original_get(*args, **kwargs)
        if getattr(message, "sentence_id", None) == "stop":
            stop_event.set()
            raise queue.Empty
        return message

    provider.tts_text_queue.get = get_once
    provider.tts_text_priority_thread()
    assert reporter.events == []


def test_index_stream_http_failure_emits_failed_without_completed(monkeypatch):
    module = importlib.import_module("core.providers.tts.index_stream")
    provider = object.__new__(module.TTSProvider)
    reporter = CapturingReporter()
    stop_event = threading.Event()
    provider.conn = SimpleNamespace(
        stop_event=stop_event,
        client_abort=False,
        sentence_id="sentence-a",
        emit_debug_event=lambda *args, **kwargs: reporter.emit(*args, **kwargs),
    )
    provider.tts_text_queue = queue.Queue()
    provider.tts_audio_queue = queue.Queue()
    provider._debug_tts_started_at = {}
    provider.tts_stop_request = False
    provider.processed_chars = 0
    provider.tts_text_buff = []
    provider.before_stop_play_files = []
    provider._correct_words_pattern = None
    provider.voice = "voice"
    provider.api_url = "https://tts.test"
    provider.pcm_buffer = bytearray()
    provider.opus_encoder = SimpleNamespace(
        sample_rate=24000, channels=1, frame_size_ms=60
    )
    monkeypatch.setattr(
        module.aiohttp,
        "ClientSession",
        lambda: FakeHttpSession(response=FakeHttpResponse(status=503)),
    )
    provider.tts_text_queue.put(
        TTSMessageDTO("sentence-a", SentenceType.FIRST, ContentType.ACTION)
    )
    provider.tts_text_queue.put(
        TTSMessageDTO(
            "sentence-a",
            SentenceType.MIDDLE,
            ContentType.TEXT,
            content_detail="hello。",
        )
    )

    original_emit_failed = provider._emit_tts_failed

    def emit_failed(*args, **kwargs):
        result = original_emit_failed(*args, **kwargs)
        stop_event.set()
        return result

    provider._emit_tts_failed = emit_failed
    provider.tts_text_priority_thread()
    assert event_types(reporter) == ["tts.started", "tts.failed"]


class FakeHttpResponse:
    def __init__(self, status=200, chunks=(), text="error"):
        self.status = status
        self.content = SimpleNamespace(iter_any=lambda: _async_chunks(chunks))
        self._text = text

    async def text(self):
        return self._text

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return None


async def _async_chunks(chunks):
    for chunk in chunks:
        yield chunk


class FakeHttpSession:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return None

    def post(self, *args, **kwargs):
        if self.error:
            raise self.error
        return self.response


@pytest.mark.parametrize("module_name", ["index_stream", "minimax_httpstream"])
@pytest.mark.parametrize("failure_kind", ["http", "runtime"])
def test_http_stream_tts_failures_raise_to_lifecycle(module_name, failure_kind, monkeypatch):
    module = importlib.import_module(f"core.providers.tts.{module_name}")
    provider = object.__new__(module.TTSProvider)
    provider.api_url = "https://tts.test"
    provider.voice = "voice"
    provider.current_expression = None
    provider.model = "model"
    provider.voice_setting = {}
    provider.pronunciation_dict = {}
    provider.audio_setting = {}
    provider.timber_weights = []
    provider.header = {}
    provider.pcm_buffer = bytearray()
    provider.tts_audio_queue = queue.Queue()
    provider.opus_encoder = SimpleNamespace(
        sample_rate=24000,
        channels=1,
        frame_size_ms=60,
        encode_pcm_to_opus_stream=lambda *args, **kwargs: None,
    )
    provider._process_before_stop_play_files = lambda: None
    if failure_kind == "http":
        session = FakeHttpSession(response=FakeHttpResponse(status=503))
    else:
        session = FakeHttpSession(error=RuntimeError("network failed"))
    monkeypatch.setattr(module.aiohttp, "ClientSession", lambda: session)
    with pytest.raises(RuntimeError):
        asyncio.run(provider.text_to_speak("hello", True))


def test_minimax_business_error_raises_to_lifecycle(monkeypatch):
    module = importlib.import_module("core.providers.tts.minimax_httpstream")
    provider = object.__new__(module.TTSProvider)
    provider.api_url = "https://tts.test"
    provider.current_expression = None
    provider.model = "model"
    provider.voice_setting = {}
    provider.pronunciation_dict = {}
    provider.audio_setting = {}
    provider.timber_weights = []
    provider.header = {}
    provider.pcm_buffer = bytearray()
    provider.tts_audio_queue = queue.Queue()
    provider.opus_encoder = SimpleNamespace(
        sample_rate=24000, channels=1, frame_size_ms=60
    )
    chunk = b'data: {"base_resp":{"status_code":1001,"status_msg":"bad"}}\n\n'
    monkeypatch.setattr(
        module.aiohttp,
        "ClientSession",
        lambda: FakeHttpSession(response=FakeHttpResponse(chunks=[chunk])),
    )
    with pytest.raises(RuntimeError):
        asyncio.run(provider.text_to_speak("hello", True))
