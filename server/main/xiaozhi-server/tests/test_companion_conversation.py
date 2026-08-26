import asyncio
import gc
import importlib
import queue
import sys
import tempfile
import threading
import types
import unittest
import warnings
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import AsyncMock, patch

from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module

modules_initialize = types.ModuleType("core.utils.modules_initialize")
modules_initialize.initialize_modules = lambda *args, **kwargs: {}
modules_initialize.initialize_tts = lambda *args, **kwargs: None
modules_initialize.initialize_asr = lambda *args, **kwargs: None
sys.modules["core.utils.modules_initialize"] = modules_initialize

text_handle = types.ModuleType("core.handle.textHandle")
handled_text_messages = []


async def fake_handle_text_message(conn, message):
    handled_text_messages.append(message)


text_handle.handleTextMessage = fake_handle_text_message
sys.modules["core.handle.textHandle"] = text_handle

tool_handler = types.ModuleType("core.providers.tools.unified_tool_handler")
tool_handler.UnifiedToolHandler = object
sys.modules["core.providers.tools.unified_tool_handler"] = tool_handler

plugin_loader = types.ModuleType("plugins_func.loadplugins")
plugin_loader.auto_import_modules = lambda *args, **kwargs: None
sys.modules["plugins_func.loadplugins"] = plugin_loader

from core.companion.identity import CompanionIdentity
from core.companion.streaming_reply import CompanionStreamingReply
from core.connection import ConnectionHandler
from config.manage_api_client import ManageApiClient
from core.providers.memory.mem_local_short.mem_local_short import (
    MemoryProvider as LocalMemory,
)
from core.providers.tts.dto.dto import ContentType, SentenceType
from core.utils.dialogue import Message
from plugins_func.register import Action


def teardown_module():
    sys.modules.pop("core.providers.tools.unified_tool_handler", None)
    importlib.import_module("core.providers.tools.unified_tool_handler")


class LoopThread:
    def __init__(self):
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=2)
        self.loop.close()

    def flush(self):
        future = asyncio.run_coroutine_threadsafe(asyncio.sleep(0), self.loop)
        future.result(timeout=2)


class FakeStreamingLlm:
    def __init__(self, chunks):
        self.chunks = chunks

    def response(self, session_id, dialogue):
        return iter(self.chunks)


class FakeMemory:
    def __init__(self, result):
        self.result = result
        self.queries = []

    async def query_memory(self, query):
        self.queries.append(query)
        return self.result


class FailingMemory:
    async def query_memory(self, query):
        raise RuntimeError("credential=secret")


class SharedBackendMemory:
    def __init__(self, backend):
        self.backend = backend
        self.memory_namespace = None

    def init_memory(self, memory_namespace, llm, **kwargs):
        self.memory_namespace = memory_namespace

    async def save_memory(self, msgs, session_id=None):
        self.backend[self.memory_namespace] = msgs[-1].content

    async def query_memory(self, query):
        return self.backend.get(self.memory_namespace, "")

    async def clear_memory(self):
        self.backend.pop(self.memory_namespace, None)
        return True


class FakeManagerResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload

    async def aclose(self):
        return None


class FakeManagerHttpClient:
    def __init__(self, private_configs, requests):
        self.private_configs = private_configs
        self.requests = requests

    async def request(self, method, endpoint, **kwargs):
        request_json = kwargs["json"]
        self.requests.append((endpoint, request_json))
        if endpoint == "config/agent-models":
            return FakeManagerResponse(
                {
                    "code": 0,
                    "data": self.private_configs[request_json["macAddress"]],
                }
            )
        return FakeManagerResponse({"code": 0, "data": None})


@contextmanager
def isolated_manage_api_client_state():
    missing = object()
    state_names = (
        "_instance",
        "config",
        "_secret",
        "max_retries",
        "retry_delay",
        "_async_clients",
    )
    original_state = {
        name: vars(ManageApiClient).get(name, missing) for name in state_names
    }
    original_clients = original_state["_async_clients"]
    original_client_entries = dict(original_clients)
    ManageApiClient._instance = None
    try:
        yield
    finally:
        original_clients.clear()
        original_clients.update(original_client_entries)
        for name, value in original_state.items():
            if value is missing:
                if hasattr(ManageApiClient, name):
                    delattr(ManageApiClient, name)
            else:
                setattr(ManageApiClient, name, value)


class FakeWebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, message):
        self.messages.append(message)


class FailingWebSocket:
    async def send(self, message):
        raise RuntimeError("send failed")


class FakeTts:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.stored = []

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    async def open_audio_channels(self, connection):
        return None

    def tts_one_sentence(self, conn, content_type, content_detail=None, **kwargs):
        self.tts_text_queue.put(
            types.SimpleNamespace(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=content_type,
                content_detail=content_detail,
            )
        )


class FakeAsr:
    async def open_audio_channels(self, connection):
        return None


class CapturingReporter:
    def __init__(self):
        self.events = []

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
        return None


class CompanionConversationTest(unittest.TestCase):
    def test_memory_query_failure_emits_safe_terminal_event(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-streaming", "Memory": "broken-memory"},
            "Memory": {"broken-memory": {"type": "remote-memory"}},
            "companion": {"enabled": False},
        }
        loop_thread = LoopThread()
        connection = ConnectionHandler(
            config,
            None,
            None,
            FakeStreamingLlm(["不会到这里"]),
            FailingMemory(),
            None,
        )
        connection.loop = loop_thread.loop
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        reporter = CapturingReporter()
        connection.debug_events = reporter
        try:
            self.assertIsNone(connection.chat("记得我吗"))
        finally:
            connection.executor.shutdown(wait=False)
            loop_thread.close()

        memory_events = [
            event
            for event in reporter.events
            if event["eventType"].startswith("memory.query_")
        ]
        self.assertEqual(
            ["memory.query_started", "memory.query_failed"],
            [event["eventType"] for event in memory_events],
        )
        self.assertEqual(
            {"errorClass": "RuntimeError"}, memory_events[-1]["details"]
        )
        self.assertNotIn("secret", str(reporter.events).lower())

    def test_direct_tool_reply_finishes_llm_lifecycle_once(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-tools"},
        }
        connection = ConnectionHandler(config, None, None, None, None, None)
        connection.tts = FakeTts()
        connection.sentence_id = "sentence-tool"
        connection._debug_llm_started_at[connection.sentence_id] = 1.0
        reporter = CapturingReporter()
        connection.debug_events = reporter
        try:
            connection._handle_function_result(
                [
                    (
                        types.SimpleNamespace(
                            action=Action.RESPONSE,
                            response="今天晴天。",
                            result=None,
                        ),
                        {"name": "weather", "id": "call-a", "arguments": "{}"},
                    )
                ],
                depth=0,
            )
        finally:
            connection.executor.shutdown(wait=False)

        self.assertEqual(
            ["llm.completed", "conversation.assistant"],
            [event["eventType"] for event in reporter.events],
        )
        self.assertEqual({"text": "今天晴天。"}, reporter.events[-1]["details"])

    def test_llm_lifecycle_reports_final_visible_reply_once(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-streaming"},
            "companion": {"enabled": False},
        }
        connection = ConnectionHandler(
            config,
            None,
            None,
            FakeStreamingLlm(["我", "在。"]),
            None,
            None,
        )
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        reporter = CapturingReporter()
        connection.debug_events = reporter
        try:
            self.assertTrue(connection.chat("你好"))
        finally:
            connection.executor.shutdown(wait=False)

        lifecycle_events = [
            event
            for event in reporter.events
            if event["eventType"].startswith("llm.")
            or event["eventType"] == "memory.query_skipped"
            or event["eventType"] == "conversation.assistant"
        ]
        self.assertEqual(
            [
                "llm.started",
                "memory.query_skipped",
                "llm.first_visible",
                "llm.completed",
                "conversation.assistant",
            ],
            [event["eventType"] for event in lifecycle_events],
        )
        self.assertEqual(
            {
                "selected_module": "fake-streaming",
                "userText": "你好",
                "toolMode": "chat",
            },
            lifecycle_events[0]["details"],
        )
        self.assertEqual(
            {"reason": "memory_disabled"}, lifecycle_events[1]["details"]
        )
        self.assertEqual(
            {"textLength": 1}, lifecycle_events[2]["details"]
        )
        self.assertEqual(
            {"outputLength": 3, "text": "我在。"}, lifecycle_events[3]["details"]
        )
        self.assertEqual({"text": "我在。"}, lifecycle_events[4]["details"])
        self.assertEqual(connection.sentence_id, lifecycle_events[4]["sentenceId"])

    def test_llm_failure_reports_only_safe_error_metadata(self):
        class FailingLlm:
            def response(self, session_id, dialogue):
                raise RuntimeError("systemPrompt=secret messages=secret")

        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "failing"},
            "companion": {"enabled": False},
        }
        connection = ConnectionHandler(config, None, None, FailingLlm(), None, None)
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        reporter = CapturingReporter()
        connection.debug_events = reporter
        try:
            self.assertIsNone(connection.chat("你好"))
        finally:
            connection.executor.shutdown(wait=False)

        lifecycle_events = [
            event
            for event in reporter.events
            if event["eventType"].startswith("llm.")
            or event["eventType"] == "memory.query_skipped"
        ]
        self.assertEqual(
            ["llm.started", "memory.query_skipped", "llm.failed"],
            [event["eventType"] for event in lifecycle_events],
        )
        self.assertEqual(
            {"errorClass": "RuntimeError"}, lifecycle_events[-1]["details"]
        )
        self.assertNotIn("secret", str(reporter.events).lower())

    def test_mixed_direct_answer_and_real_tool_waits_for_final_tool_reply(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-tools"},
            "companion": {"enabled": False},
        }

        class MixedToolLlm:
            def __init__(self):
                self.calls = 0

            def response_with_functions(self, session_id, dialogue, functions):
                self.calls += 1
                if self.calls == 1:
                    direct = types.SimpleNamespace(
                        index=0,
                        id="direct-a",
                        function=types.SimpleNamespace(
                            name="direct_answer",
                            arguments='{"response":"先等等。"}',
                        ),
                    )
                    tool = types.SimpleNamespace(
                        index=1,
                        id="weather-a",
                        function=types.SimpleNamespace(
                            name="weather",
                            arguments='{"city":"上海"}',
                        ),
                    )
                    return iter([(None, [direct, tool])])
                return iter([("工具后的最终回复。", None)])

        class FakeToolHandler:
            def get_functions(self, allowed_names=None):
                return [{"type": "function", "function": {"name": "weather"}}]

            async def handle_llm_function_call(self, conn, call):
                return types.SimpleNamespace(
                    action=Action.REQLLM,
                    result="晴天",
                    response=None,
                )

        loop_thread = LoopThread()
        connection = ConnectionHandler(
            config, None, None, MixedToolLlm(), None, None
        )
        connection.loop = loop_thread.loop
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        connection.intent_type = "function_call"
        connection.func_handler = FakeToolHandler()
        reporter = CapturingReporter()
        connection.debug_events = reporter
        try:
            self.assertTrue(connection.chat("天气如何"))
        finally:
            connection.executor.shutdown(wait=False)
            loop_thread.close()

        assistant_events = [
            event
            for event in reporter.events
            if event["eventType"] == "conversation.assistant"
        ]
        self.assertEqual(1, len(assistant_events))
        self.assertEqual(
            {"text": "工具后的最终回复。"}, assistant_events[0]["details"]
        )

    def test_direct_answer_metadata_never_reaches_companion_tts(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-tools"},
            "companion": {"enabled": True},
            "tools_for_chat": True,
        }

        class DirectAnswerLlm:
            tools_enabled = True

            def response_with_functions(self, session_id, dialogue, functions):
                call = types.SimpleNamespace(
                    index=0,
                    id="direct-a",
                    function=types.SimpleNamespace(
                        name="direct_answer",
                        arguments=(
                            '{"response":"{“emotion”:“gentle”,“cue”:null}\\n'
                            '我叫紫萱。"}'
                        ),
                    ),
                )
                return iter([(None, [call])])

        class FakeToolHandler:
            def get_functions(self, allowed_names=None):
                return [{
                    "type": "function",
                    "function": {"name": "handle_exit_intent"},
                }]

        loop_thread = LoopThread()
        connection = ConnectionHandler(
            config, None, None, DirectAnswerLlm(), None, None
        )
        connection.loop = loop_thread.loop
        connection.websocket = FakeWebSocket()
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        connection.intent_type = "function_call"
        connection.func_handler = FakeToolHandler()
        try:
            connection.chat("你是谁呀？")
            loop_thread.flush()
        finally:
            connection.executor.shutdown(wait=False)
            loop_thread.close()

        spoken = "".join(
            message.content_detail
            for message in connection.tts.tts_text_queue.queue
            if message.content_type == ContentType.TEXT
        )
        self.assertEqual("我叫紫萱。", spoken)

    def test_tool_response_metadata_never_reaches_companion_tts(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-tools"},
            "companion": {"enabled": True},
            "tools_for_chat": True,
        }

        class ExitToolLlm:
            tools_enabled = True

            def response_with_functions(self, session_id, dialogue, functions):
                call = types.SimpleNamespace(
                    index=0,
                    id="exit-a",
                    function=types.SimpleNamespace(
                        name="handle_exit_intent",
                        arguments='{"say_goodbye":"再见"}',
                    ),
                )
                return iter([(None, [call])])

        class FakeToolHandler:
            def get_functions(self, allowed_names=None):
                return [{
                    "type": "function",
                    "function": {"name": "handle_exit_intent"},
                }]

            async def handle_llm_function_call(self, conn, call):
                return types.SimpleNamespace(
                    action=Action.RESPONSE,
                    result="退出意图已处理",
                    response="{“emotion”:“sad”,“cue”:null}\n下次再聊。",
                )

        loop_thread = LoopThread()
        connection = ConnectionHandler(
            config, None, None, ExitToolLlm(), None, None
        )
        connection.loop = loop_thread.loop
        connection.websocket = FakeWebSocket()
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        connection.intent_type = "function_call"
        connection.func_handler = FakeToolHandler()
        try:
            connection.chat("结束对话")
            loop_thread.flush()
        finally:
            connection.executor.shutdown(wait=False)
            loop_thread.close()

        spoken = "".join(
            message.content_detail
            for message in connection.tts.tts_text_queue.queue
            if message.content_type == ContentType.TEXT
        )
        self.assertEqual("下次再聊。", spoken)

    def test_post_tool_llm_metadata_never_reaches_companion_tts(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"LLM": "fake-tools"},
            "companion": {"enabled": True},
            "tools_for_chat": True,
        }

        class ToolThenAnswerLlm:
            tools_enabled = True

            def __init__(self):
                self.calls = 0

            def response_with_functions(self, session_id, dialogue, functions):
                self.calls += 1
                if self.calls == 1:
                    call = types.SimpleNamespace(
                        index=0,
                        id="weather-a",
                        function=types.SimpleNamespace(
                            name="weather",
                            arguments='{"city":"深圳"}',
                        ),
                    )
                    return iter([(None, [call])])
                return iter([
                    ("{“emotion”:“happy”,“cue”:null}\n", None),
                    ("深圳今天晴天。", None),
                ])

        class FakeToolHandler:
            def get_functions(self, allowed_names=None):
                return [{
                    "type": "function",
                    "function": {"name": "weather"},
                }]

            async def handle_llm_function_call(self, conn, call):
                return types.SimpleNamespace(
                    action=Action.REQLLM,
                    result="深圳今天晴天",
                    response=None,
                )

        loop_thread = LoopThread()
        connection = ConnectionHandler(
            config, None, None, ToolThenAnswerLlm(), None, None
        )
        connection.loop = loop_thread.loop
        connection.websocket = FakeWebSocket()
        connection.tts = FakeTts()
        connection.features = {"emoji": False}
        connection.intent_type = "function_call"
        connection.func_handler = FakeToolHandler()
        try:
            connection.chat("深圳天气怎么样？")
            loop_thread.flush()
        finally:
            connection.executor.shutdown(wait=False)
            loop_thread.close()

        spoken = "".join(
            message.content_detail
            for message in connection.tts.tts_text_queue.queue
            if message.content_type == ContentType.TEXT
        )
        self.assertEqual("深圳今天晴天。", spoken)

    def test_worker_thread_notifies_component_readiness_on_event_loop(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {},
        }
        connection = ConnectionHandler(config, None, None, None, None, None)
        connection.tts = FakeTts()
        connection.vad = object()
        connection.asr = FakeAsr()
        connection._initialize_voiceprint = lambda: None
        connection._initialize_memory = lambda: None
        connection._initialize_intent = lambda: None
        connection._init_report_threads = lambda: None
        connection._init_prompt_enhancement = lambda: None
        allow_worker_to_finish = threading.Event()
        connection._inject_tool_call_fewshot = lambda: allow_worker_to_finish.wait(1)

        async def scenario():
            connection.loop = asyncio.get_running_loop()
            connection.loop.set_debug(True)
            worker = threading.Thread(target=connection._initialize_components)
            worker.start()
            ready_waiter = asyncio.create_task(
                connection.components_ready_event.wait()
            )
            await asyncio.sleep(0)
            allow_worker_to_finish.set()
            await asyncio.wait_for(ready_waiter, timeout=1)
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())

        try:
            asyncio.run(scenario())
        finally:
            connection.executor.shutdown(wait=False)

    def test_worker_thread_waits_for_audio_channels_before_readiness(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {},
        }
        connection = ConnectionHandler(config, None, None, None, None, None)
        tts_gate = asyncio.Event()
        asr_gate = asyncio.Event()
        tts_started = threading.Event()
        asr_started = threading.Event()

        class GatedTts(FakeTts):
            async def open_audio_channels(self, _connection):
                tts_started.set()
                await tts_gate.wait()

        class GatedAsr(FakeAsr):
            async def open_audio_channels(self, _connection):
                asr_started.set()
                await asr_gate.wait()

        connection.tts = GatedTts()
        connection.vad = object()
        connection.asr = GatedAsr()
        connection._initialize_voiceprint = lambda: None
        connection._initialize_memory = lambda: None
        connection._initialize_intent = lambda: None
        connection._init_report_threads = lambda: None
        connection._init_prompt_enhancement = lambda: None
        connection._inject_tool_call_fewshot = lambda: None

        async def scenario():
            connection.loop = asyncio.get_running_loop()
            worker = threading.Thread(target=connection._initialize_components)
            worker.start()
            await asyncio.to_thread(tts_started.wait, 1)
            await asyncio.to_thread(asr_started.wait, 1)
            await asyncio.sleep(0)
            self.assertFalse(connection.components_ready_event.is_set())

            tts_gate.set()
            asr_gate.set()
            await asyncio.wait_for(connection.components_ready_event.wait(), timeout=1)
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())

        try:
            asyncio.run(scenario())
        finally:
            connection.executor.shutdown(wait=False)

    def test_listen_message_waits_for_connection_components(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
        }
        connection = ConnectionHandler(config, None, None, None, None, None)

        async def scenario():
            connection.bind_completed_event.set()
            connection.components_ready_event = asyncio.Event()
            message = '{"type":"listen","state":"start","mode":"auto"}'
            task = asyncio.create_task(connection._route_message(message))
            await asyncio.sleep(0)
            self.assertFalse(task.done())

            connection.components_ready_event.set()
            await task
            self.assertIn(message, handled_text_messages)

        try:
            asyncio.run(scenario())
        finally:
            connection.executor.shutdown(wait=False)

    def test_connections_do_not_share_memory_provider_state(self):
        namespace_a = "companion:" + "a" * 64
        namespace_b = "companion:" + "b" * 64
        base_config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "selected_module": {"Memory": "mem_local_short"},
            "Memory": {
                "mem_local_short": {
                    "type": "mem_local_short",
                    "llm": None,
                }
            },
        }
        shared_memory = LocalMemory({}, summary_memory=None)
        connection_a = ConnectionHandler(
            base_config, None, None, None, shared_memory, None
        )
        connection_b = ConnectionHandler(
            base_config, None, None, None, shared_memory, None
        )
        connection_a.companion_identity = CompanionIdentity(
            7, "agent", "device-a", namespace_a
        )
        connection_b.companion_identity = CompanionIdentity(
            7, "agent", "device-b", namespace_b
        )
        try:
            connection_a._initialize_memory()
            connection_b._initialize_memory()

            self.assertIsNot(connection_a.memory, connection_b.memory)
            self.assertEqual(namespace_a, connection_a.memory.memory_namespace)
            self.assertEqual(namespace_b, connection_b.memory.memory_namespace)
            self.assertEqual(
                {
                    "source_user_id": 7,
                    "source_device_id": "device-a",
                    "source_profile_id": "agent",
                },
                connection_a.memory.source_metadata,
            )
        finally:
            connection_a.executor.shutdown(wait=False)
            connection_b.executor.shutdown(wait=False)

    def test_manager_identities_isolate_memory_across_connections(self):
        namespace_a = "companion:" + "a" * 64
        namespace_b = "companion:" + "b" * 64
        base_config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "read_config_from_api": True,
            "manager-api": {
                "url": "http://manager.test",
                "secret": "test-secret",
                "max_retries": 0,
            },
            "selected_module": {"Memory": "shared_test_memory"},
            "Memory": {
                "shared_test_memory": {
                    "type": "shared_test_memory",
                    "llm": None,
                }
            },
        }
        private_configs = {
            "AA:BB:CC:DD:EE:01": {
                "selected_module": {"Memory": "shared_test_memory"},
                "Memory": {
                    "shared_test_memory": {
                        "type": "shared_test_memory",
                        "llm": None,
                    }
                },
                "companion_identity": {
                    "user_id": 7,
                    "agent_id": "agent-id",
                    "device_id": "binding-id-a",
                    "memory_namespace": namespace_a,
                },
            },
            "AA:BB:CC:DD:EE:02": {
                "selected_module": {"Memory": "shared_test_memory"},
                "Memory": {
                    "shared_test_memory": {
                        "type": "shared_test_memory",
                        "llm": None,
                    }
                },
                "companion_identity": {
                    "user_id": 7,
                    "agent_id": "agent-id",
                    "device_id": "binding-id-b",
                    "memory_namespace": namespace_b,
                },
            },
        }
        http_requests = []
        backend = {}
        http_client = FakeManagerHttpClient(private_configs, http_requests)

        def initialize_private_modules(*args, **kwargs):
            return {"memory": object()}

        connection_a = ConnectionHandler(
            base_config, None, None, None, None, None
        )
        connection_b = ConnectionHandler(
            base_config, None, None, None, None, None
        )
        connection_a.headers = {"device-id": "AA:BB:CC:DD:EE:01"}
        connection_b.headers = {"device-id": "AA:BB:CC:DD:EE:02"}
        preserved_instance = object()
        preserved_config = object()
        preserved_secret = object()
        preserved_async_client = object()
        preserved_async_clients = {"existing-loop": preserved_async_client}
        class_state_patchers = [
            patch.object(ManageApiClient, "_instance", preserved_instance),
            patch.object(ManageApiClient, "config", preserved_config, create=True),
            patch.object(ManageApiClient, "_secret", preserved_secret),
            patch.object(ManageApiClient, "max_retries", 17, create=True),
            patch.object(ManageApiClient, "retry_delay", 23, create=True),
            patch.object(
                ManageApiClient, "_async_clients", preserved_async_clients
            ),
        ]
        for state_patcher in class_state_patchers:
            state_patcher.start()
            self.addCleanup(state_patcher.stop)

        async def scenario():
            connection_a.loop = asyncio.get_running_loop()
            connection_b.loop = asyncio.get_running_loop()
            await connection_a._initialize_private_config_async()
            await connection_b._initialize_private_config_async()
            connection_a._initialize_memory()
            connection_b._initialize_memory()

            await connection_a.memory.save_memory(
                [Message(role="user", content="A 喜欢松果")]
            )
            await connection_b.memory.save_memory(
                [Message(role="user", content="B 喜欢月亮")]
            )
            self.assertEqual(
                "A 喜欢松果", await connection_a.memory.query_memory("A 喜欢什么")
            )
            self.assertEqual(
                "B 喜欢月亮", await connection_b.memory.query_memory("B 喜欢什么")
            )

            self.assertTrue(await connection_a.memory.clear_memory())
            self.assertEqual("", await connection_a.memory.query_memory("A 喜欢什么"))
            self.assertEqual(
                "B 喜欢月亮", await connection_b.memory.query_memory("B 喜欢什么")
            )

        try:
            with isolated_manage_api_client_state(), patch.object(
                ManageApiClient,
                "_ensure_async_client",
                new=AsyncMock(return_value=http_client),
            ), patch(
                "core.connection.initialize_modules",
                side_effect=initialize_private_modules,
            ), patch(
                "core.connection.memory_utils.create_instance",
                side_effect=lambda *args, **kwargs: SharedBackendMemory(backend),
            ):
                ManageApiClient(base_config)
                asyncio.run(scenario())
        finally:
            connection_a.executor.shutdown(wait=False)
            connection_b.executor.shutdown(wait=False)

        agent_requests = [
            request_json
            for endpoint, request_json in http_requests
            if endpoint == "config/agent-models"
        ]
        self.assertEqual(
            ["AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"],
            [request["macAddress"] for request in agent_requests],
        )
        self.assertEqual(
            ["AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"],
            [request["clientId"] for request in agent_requests],
        )
        self.assertEqual("binding-id-a", connection_a.companion_identity.device_id)
        self.assertEqual(namespace_a, connection_a.memory.memory_namespace)
        self.assertEqual("binding-id-b", connection_b.companion_identity.device_id)
        self.assertEqual(namespace_b, connection_b.memory.memory_namespace)
        self.assertIs(preserved_instance, ManageApiClient._instance)
        self.assertIs(preserved_config, ManageApiClient.config)
        self.assertIs(preserved_secret, ManageApiClient._secret)
        self.assertEqual(17, ManageApiClient.max_retries)
        self.assertEqual(23, ManageApiClient.retry_delay)
        self.assertIs(preserved_async_clients, ManageApiClient._async_clients)
        self.assertEqual(["existing-loop"], list(preserved_async_clients))
        self.assertIs(
            preserved_async_client, preserved_async_clients["existing-loop"]
        )

    def test_local_config_loads_companion_identity(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "companion": {"enabled": True},
            "companion_identity": {
                "user_id": 7,
                "agent_id": "local-agent",
                "device_id": "local-device",
                "memory_namespace": "companion:" + "b" * 64,
            }
        }
        connection = ConnectionHandler(config, None, None, None, None, None)
        try:
            asyncio.run(connection._initialize_private_config_async())
        finally:
            connection.executor.shutdown(wait=False)

        self.assertIsNotNone(connection.companion_identity)
        self.assertEqual(
            "companion:" + "b" * 64,
            connection.companion_identity.memory_namespace,
        )

    def test_private_companion_config_reaches_runtime_consumers(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
            config = {
                "exit_commands": ["退出"],
                "close_connection_no_voice_time": 120,
                "read_config_from_api": True,
                "selected_module": {},
                "companion": {
                    "enabled": False,
                    "persona_prompt": "local prompt",
                    "relation_mode": "friend",
                    "cue_files": {"laugh": "local-laugh.wav"},
                },
            }
            private_config = {
                "companion": {
                    "enabled": True,
                    "persona_prompt": "profile prompt",
                    "cue_files": {"sigh": "sigh.wav"},
                }
            }
            connection = ConnectionHandler(config, None, None, None, None, None)
            connection.headers = {"device-id": "device-a"}

            async def scenario():
                connection.loop = asyncio.get_running_loop()
                with patch(
                    "core.connection.get_private_config_from_api",
                    new=AsyncMock(return_value=private_config),
                ):
                    await connection._initialize_private_config_async()

            try:
                asyncio.run(scenario())
                self.assertEqual(
                    "friend", connection.config["companion"]["relation_mode"]
                )
                self.assertEqual(
                    "profile prompt",
                    connection.prompt_manager._get_effective_prompt("fallback"),
                )
                self.assertEqual(
                    {"sigh": "sigh.wav"},
                    connection.config["companion"]["cue_files"],
                )

                messages = queue.Queue()
                reply = CompanionStreamingReply(
                    "sentence-id",
                    messages,
                    companion_config=connection.config["companion"],
                    cue_resource_root=directory,
                )
                reply.feed('{"emotion":"gentle","cue":"sigh"}\n你好')
                self.assertEqual(
                    str(cue_path.resolve()),
                    next(
                        message.content_file
                        for message in messages.queue
                        if message.content_type == ContentType.FILE
                    ),
                )
            finally:
                connection.executor.shutdown(wait=False)

    def test_missing_private_companion_preserves_local_companion_config(self):
        local_companion = {
            "enabled": True,
            "persona_prompt": "local prompt",
            "cue_files": {"sigh": "local-sigh.wav"},
        }
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "read_config_from_api": True,
            "selected_module": {},
            "companion": local_companion,
        }
        connection = ConnectionHandler(config, None, None, None, None, None)
        connection.headers = {"device-id": "device-a"}

        async def scenario():
            connection.loop = asyncio.get_running_loop()
            with patch(
                "core.connection.get_private_config_from_api",
                new=AsyncMock(return_value={}),
            ):
                await connection._initialize_private_config_async()

        try:
            asyncio.run(scenario())
        finally:
            connection.executor.shutdown(wait=False)

        self.assertEqual(local_companion, connection.config["companion"])
        self.assertIsNot(local_companion, connection.config["companion"])

    def test_private_profile_configs_remain_isolated_between_connections(self):
        base_config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "read_config_from_api": True,
            "selected_module": {},
            "companion": {"enabled": False, "persona_prompt": "local"},
        }
        profiles = {
            "device-a": {
                "companion": {
                    "enabled": True,
                    "persona_prompt": "profile-a",
                    "cue_files": {"sigh": "profile-a.wav"},
                }
            },
            "device-b": {
                "companion": {
                    "enabled": True,
                    "persona_prompt": "profile-b",
                    "cue_files": {"laugh": "profile-b.wav"},
                }
            },
        }
        connection_a = ConnectionHandler(base_config, None, None, None, None, None)
        connection_b = ConnectionHandler(base_config, None, None, None, None, None)
        connection_a.headers = {"device-id": "device-a"}
        connection_b.headers = {"device-id": "device-b"}

        async def load_private_config(_config, device_id, _client_id):
            return profiles[device_id]

        async def scenario():
            connection_a.loop = asyncio.get_running_loop()
            connection_b.loop = asyncio.get_running_loop()
            with patch(
                "core.connection.get_private_config_from_api",
                side_effect=load_private_config,
            ):
                await connection_a._initialize_private_config_async()
                await connection_b._initialize_private_config_async()

        try:
            asyncio.run(scenario())
        finally:
            connection_a.executor.shutdown(wait=False)
            connection_b.executor.shutdown(wait=False)

        self.assertEqual(
            "profile-a", connection_a.config["companion"]["persona_prompt"]
        )
        self.assertEqual(
            "profile-b", connection_b.config["companion"]["persona_prompt"]
        )
        connection_a.config["companion"]["cue_files"]["sigh"] = "changed.wav"
        self.assertEqual(
            {"laugh": "profile-b.wav"},
            connection_b.config["companion"]["cue_files"],
        )
        self.assertEqual("local", base_config["companion"]["persona_prompt"])
        self.assertIsNot(
            connection_a.config["companion"], connection_b.config["companion"]
        )

    def test_memory_reply_emotion_and_audio_queue_form_one_turn(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path("config/assets/companion/breathe.wav").resolve()
            config = {
                "exit_commands": ["退出"],
                "close_connection_no_voice_time": 120,
                "companion": {
                    "enabled": True,
                    "cue_files": {
                        "breathe": "config/assets/companion/breathe.wav"
                    },
                },
            }
            llm = FakeStreamingLlm([
                '{"emotion":"gentle","cue":"breathe"}\n',
                "记得。你今天去见了那位老朋友。",
            ])
            memory = FakeMemory("用户今天要见一位老朋友")
            tts = FakeTts()
            loop_thread = LoopThread()
            connection = ConnectionHandler(config, None, None, llm, memory, None)
            connection.loop = loop_thread.loop
            connection.tts = tts
            connection.websocket = FakeWebSocket()
            connection.features = {"emoji": True}
            reporter = CapturingReporter()
            connection.debug_events = reporter
            log_records = []
            log_sink = logger.add(lambda message: log_records.append(message.record))
            connection.companion_identity = CompanionIdentity(
                7, "agent-id", "device-id", "companion:" + "a" * 64
            )
            connection.dialogue.put(Message(role="system", content="<memory></memory>"))
            try:
                connection.chat("你还记得我今天做什么吗？")
                asyncio.run_coroutine_threadsafe(
                    asyncio.sleep(0), loop_thread.loop
                ).result(timeout=1)
            finally:
                logger.remove(log_sink)
                connection.executor.shutdown(wait=False)
                loop_thread.close()

            self.assertEqual(["你还记得我今天做什么吗？"], memory.queries)
            memory_events = [
                event
                for event in reporter.events
                if event["eventType"].startswith("memory.query_")
            ]
            self.assertEqual(
                ["memory.query_started", "memory.query_completed"],
                [event["eventType"] for event in memory_events],
            )
            self.assertEqual(
                {
                    "queryLength": 12,
                    "hit": True,
                    "resultLength": 11,
                },
                memory_events[-1]["details"],
            )
            self.assertNotIn("你还记得", str(memory_events))
            self.assertNotIn("老朋友", str(memory_events))
            messages = list(tts.tts_text_queue.queue)
            spoken = "".join(
                message.content_detail or ""
                for message in messages
                if message.content_type == ContentType.TEXT
            )
            self.assertNotIn("emotion", spoken)
            self.assertIn("老朋友", spoken)
            self.assertEqual(SentenceType.FIRST, messages[0].sentence_type)
            self.assertEqual("relaxed", messages[0].expression.display_emotion)
            self.assertEqual(1, sum(m.content_type == ContentType.FILE for m in messages))
            self.assertEqual(SentenceType.LAST, messages[-1].sentence_type)
            companion_records = [
                record
                for record in log_records
                if record["extra"].get("event")
                in {"companion_emotion_dispatched", "companion_cue_enqueued"}
            ]
            companion_events = [
                record["extra"]["event"] for record in companion_records
            ]
            self.assertEqual(1, companion_events.count("companion_emotion_dispatched"))
            self.assertEqual(1, companion_events.count("companion_cue_enqueued"))
            self.assertEqual(2, len(companion_events))
            for record in companion_records:
                self.assertEqual(connection.session_id, record["extra"]["session_id"])
                self.assertEqual(connection.sentence_id, record["extra"]["sentence_id"])
                self.assertEqual("relaxed", record["extra"]["display_emotion"])
                self.assertEqual("breathe", record["extra"]["cue"])
            cue_record = next(
                record
                for record in companion_records
                if record["extra"]["event"] == "companion_cue_enqueued"
            )
            self.assertEqual("breathe.wav", cue_record["extra"]["cue_file"])
            self.assertNotIn(str(cue_path), cue_record["message"])

    def test_failed_emotion_send_logs_failure_without_false_success(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "companion": {"enabled": True},
        }
        llm = FakeStreamingLlm([
            '{"emotion":"gentle","cue":null}\n',
            "我在。",
        ])
        tts = FakeTts()
        loop_thread = LoopThread()
        connection = ConnectionHandler(config, None, None, llm, None, None)
        connection.loop = loop_thread.loop
        connection.tts = tts
        connection.websocket = FailingWebSocket()
        log_records = []
        log_sink = logger.add(lambda message: log_records.append(message.record))
        try:
            connection.chat("测试消息")
            asyncio.run_coroutine_threadsafe(
                asyncio.sleep(0), loop_thread.loop
            ).result(timeout=1)
        finally:
            logger.remove(log_sink)
            connection.executor.shutdown(wait=False)
            loop_thread.close()

        emotion_records = [
            record
            for record in log_records
            if record["extra"].get("event")
            in {
                "companion_emotion_dispatched",
                "companion_emotion_dispatch_failed",
            }
        ]
        self.assertEqual(
            ["companion_emotion_dispatch_failed"],
            [record["extra"]["event"] for record in emotion_records],
        )
        self.assertEqual("WARNING", emotion_records[0]["level"].name)
        self.assertEqual(connection.session_id, emotion_records[0]["extra"]["session_id"])
        self.assertEqual(connection.sentence_id, emotion_records[0]["extra"]["sentence_id"])
        self.assertEqual("relaxed", emotion_records[0]["extra"]["display_emotion"])
        self.assertIsNone(emotion_records[0]["extra"]["cue"])
        self.assertEqual(SentenceType.LAST, list(tts.tts_text_queue.queue)[-1].sentence_type)

    def test_closed_loop_logs_dispatch_failure_without_leaking_coroutine(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "companion": {"enabled": True},
        }
        llm = FakeStreamingLlm([
            '{"emotion":"gentle","cue":null}\n',
            "我在。",
        ])
        tts = FakeTts()
        closed_loop = asyncio.new_event_loop()
        closed_loop.close()
        connection = ConnectionHandler(config, None, None, llm, None, None)
        connection.loop = closed_loop
        connection.tts = tts
        connection.websocket = FakeWebSocket()
        log_records = []
        log_sink = logger.add(lambda message: log_records.append(message.record))
        try:
            with warnings.catch_warnings(record=True) as caught_warnings:
                warnings.simplefilter("always")
                connection.chat("测试消息")
                gc.collect()
        finally:
            logger.remove(log_sink)
            connection.executor.shutdown(wait=False)

        dispatch_events = [
            record["extra"]["event"]
            for record in log_records
            if record["extra"].get("event")
            in {
                "companion_emotion_dispatched",
                "companion_emotion_dispatch_failed",
            }
        ]
        self.assertEqual(["companion_emotion_dispatch_failed"], dispatch_events)
        self.assertFalse(
            any(
                "was never awaited" in str(warning.message)
                for warning in caught_warnings
            )
        )
        messages = list(tts.tts_text_queue.queue)
        self.assertEqual(SentenceType.FIRST, messages[0].sentence_type)
        self.assertEqual("我在。", "".join(
            message.content_detail
            for message in messages
            if message.content_type == ContentType.TEXT
        ))
        self.assertEqual(SentenceType.LAST, messages[-1].sentence_type)

    def test_stopped_loop_does_not_create_unawaited_dispatch_coroutine(self):
        config = {
            "exit_commands": ["退出"],
            "close_connection_no_voice_time": 120,
            "companion": {"enabled": True},
        }
        llm = FakeStreamingLlm([
            '{"emotion":"gentle","cue":null}\n',
            "我在。",
        ])
        tts = FakeTts()
        stopped_loop = asyncio.new_event_loop()
        stopped_loop.stop()
        connection = ConnectionHandler(config, None, None, llm, None, None)
        connection.loop = stopped_loop
        connection.tts = tts
        connection.websocket = FakeWebSocket()
        log_records = []
        log_sink = logger.add(lambda message: log_records.append(message.record))
        try:
            with warnings.catch_warnings(record=True) as caught_warnings:
                warnings.simplefilter("always")
                connection.chat("测试消息")
                stopped_loop.close()
                gc.collect()
        finally:
            logger.remove(log_sink)
            connection.executor.shutdown(wait=False)
            if not stopped_loop.is_closed():
                stopped_loop.close()

        dispatch_events = [
            record["extra"]["event"]
            for record in log_records
            if record["extra"].get("event")
            in {
                "companion_emotion_dispatched",
                "companion_emotion_dispatch_failed",
            }
        ]
        self.assertEqual(["companion_emotion_dispatch_failed"], dispatch_events)
        self.assertFalse(
            any(
                "was never awaited" in str(warning.message)
                for warning in caught_warnings
            )
        )
        messages = list(tts.tts_text_queue.queue)
        self.assertEqual(SentenceType.FIRST, messages[0].sentence_type)
        self.assertEqual("我在。", "".join(
            message.content_detail
            for message in messages
            if message.content_type == ContentType.TEXT
        ))
        self.assertEqual(SentenceType.LAST, messages[-1].sentence_type)


if __name__ == "__main__":
    unittest.main()
