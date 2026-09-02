import asyncio
import threading
from collections import deque
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import pytest

from core.companion.proactive_planner import ProactivePlan
from core.companion.identity import CompanionIdentity
from core.connection import ConnectionHandler
from core.utils.dialogue import Dialogue, Message


def proactive_connection(memory=None):
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection.stop_event = threading.Event()
    connection.tts = SimpleNamespace(
        tts_text_queue=__import__("queue").Queue(),
        store_tts_text=Mock(),
        tts_one_sentence=Mock(),
    )
    connection.client_is_speaking = False
    connection.client_abort = False
    connection.sentence_id = None
    connection.dialogue = Dialogue()
    connection._proactive_history = deque(maxlen=3)
    connection.memory = memory
    connection.logger = Mock()
    connection.emit_debug_event = Mock()
    connection.config = {"companion": {"enabled": True, "mode": "proactive"}}
    connection.proactive_playback_active = False
    return connection


@pytest.mark.asyncio
async def test_proactive_reply_is_saved_with_source_and_memory_metadata():
    memory = SimpleNamespace(add_memory_item=AsyncMock(return_value=True))
    connection = proactive_connection(memory)
    plan = ProactivePlan("speak", "今天也辛苦了。", "gentle", "comfort", memory_ids=("m1",))

    with patch("core.connection.send_tts_message", new=AsyncMock()):
        task = asyncio.create_task(connection._speak_proactive(plan))
        await asyncio.sleep(0)

    sentence_id = connection._proactive_sentence_id
    assert connection.dialogue.dialogue == []
    connection._on_proactive_tts_terminal(sentence_id, True)
    assert await task is True
    assistant = connection.dialogue.dialogue[-1]
    assert assistant.source == "proactive"
    await asyncio.sleep(0)
    memory.add_memory_item.assert_awaited_once()
    args, kwargs = memory.add_memory_item.await_args
    assert args[0] == "今天也辛苦了。"
    assert kwargs["source_metadata"]["source"] == "proactive"
    assert kwargs["source_metadata"]["memory_ids"] == ["m1"]


@pytest.mark.asyncio
async def test_proactive_playback_failure_clears_state_and_queue():
    connection = proactive_connection()
    connection.tts.tts_one_sentence.side_effect = RuntimeError("tts unavailable")
    plan = ProactivePlan("speak", "我在。", "neutral", "presence")

    with patch("core.connection.send_tts_message", new=AsyncMock()):
        with pytest.raises(RuntimeError):
            await connection._speak_proactive(plan)

    assert connection.client_is_speaking is False
    assert connection.proactive_playback_active is False
    assert connection.tts.tts_text_queue.empty()


@pytest.mark.asyncio
async def test_tts_terminal_failure_does_not_commit_unheard_proactive_reply():
    connection = proactive_connection()
    plan = ProactivePlan("speak", "不应该留下。", "neutral", "presence")

    with patch("core.connection.send_tts_message", new=AsyncMock()):
        task = asyncio.create_task(connection._speak_proactive(plan))
        await asyncio.sleep(0)
    sentence_id = connection._proactive_sentence_id

    connection._on_proactive_tts_terminal(sentence_id, False, RuntimeError("provider"))
    assert await task is False

    assert connection.dialogue.dialogue == []
    assert connection._pending_proactive_messages == {}
    assert connection.proactive_playback_active is False


@pytest.mark.asyncio
async def test_proactive_memory_save_skips_disabled_memory_provider():
    memory = SimpleNamespace(save_memory=AsyncMock(return_value=None))
    connection = proactive_connection(memory)
    connection.config.update({
        "selected_module": {"Memory": "disabled"},
        "Memory": {"disabled": {"type": "nomem"}},
    })
    plan = ProactivePlan("speak", "不应保存。", "neutral", "presence")

    assert await connection._save_proactive_memory(plan, "sentence-1") is False
    memory.save_memory.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("disabled_value", [0, "false"])
async def test_proactive_memory_save_skips_false_like_memory_flags(disabled_value):
    memory = SimpleNamespace(save_memory=AsyncMock(return_value=True))
    connection = proactive_connection(memory)
    connection.config["memory_enabled"] = disabled_value
    plan = ProactivePlan("speak", "不应保存。", "neutral", "presence")

    assert await connection._save_proactive_memory(plan, "sentence-1") is False
    memory.save_memory.assert_not_awaited()


@pytest.mark.asyncio
async def test_cancel_proactive_playback_resets_rate_controller_before_stop_notice():
    connection = proactive_connection()

    class RateController:
        def __init__(self):
            self.stopped = False
            self.reset_called = False
            self.queue_empty_event = asyncio.Event()

        def stop_sending(self):
            self.stopped = True

        def reset(self):
            self.reset_called = True
            self.queue_empty_event.set()

    connection.audio_rate_controller = RateController()
    connection.proactive_playback_active = True
    connection._proactive_sentence_id = "sentence"
    connection.websocket = SimpleNamespace(send=AsyncMock())
    connection.loop = asyncio.get_running_loop()

    assert connection.cancel_proactive_playback() is True
    await asyncio.sleep(0)

    assert connection.audio_rate_controller.stopped is True
    assert connection.audio_rate_controller.reset_called is True
    assert connection.proactive_playback_active is False
    assert connection.client_is_speaking is False


def test_message_keeps_a_source_for_memory_and_history_provenance():
    message = Message(role="assistant", content="我在。", source="proactive")

    assert message.source == "proactive"


def test_memory_toggle_from_companion_identity_disables_runtime_memory():
    connection = ConnectionHandler(
        {
            "exit_commands": [],
            "companion_identity": {
                "user_id": 7,
                "agent_id": "profile",
                "device_id": "device",
                "memory_enabled": False,
                "memory_namespace": "companion:" + "a" * 64,
            },
        },
        None,
        None,
        None,
        object(),
        None,
    )
    connection.companion_identity = CompanionIdentity(
        7, "profile", "device", "companion:" + "a" * 64
    )

    try:
        connection._initialize_memory()
    finally:
        connection.executor.shutdown(wait=False)

    assert connection.memory is None


def test_profile_namespace_does_not_seed_legacy_agent_summary_memory():
    provider = SimpleNamespace(init_memory=Mock())
    connection = ConnectionHandler(
        {
            "exit_commands": [],
            "selected_module": {"Memory": "profile_memory"},
            "Memory": {"profile_memory": {"type": "nomem"}},
            "summaryMemory": "旧 Agent 总结，不应混入角色记忆",
            "companion_identity": {
                "user_id": 7,
                "agent_id": "profile",
                "device_id": "device",
                "profile_memory_namespace": "companion:7:profile",
            },
        },
        None,
        None,
        None,
        provider,
        None,
    )
    connection.companion_identity = CompanionIdentity(
        7, "profile", "device", "companion:7:profile"
    )

    with patch("core.connection.memory_utils.create_instance", return_value=provider) as create:
        connection._initialize_memory()

    assert create.call_args.args[2] is None
    assert provider.init_memory.call_args.kwargs["summary_memory"] is None
    assert provider.init_memory.call_args.kwargs["save_to_file"] is True


def test_api_backed_profile_namespace_uses_shared_local_memory_persistence():
    provider = Mock()
    connection = ConnectionHandler(
        {
            "exit_commands": [],
            "read_config_from_api": True,
            "selected_module": {"Memory": "profile_memory"},
            "Memory": {"profile_memory": {"type": "mem_local_short"}},
        },
        None,
        None,
        None,
        provider,
        None,
    )
    connection.companion_identity = CompanionIdentity(
        7, "profile", "device", "companion:7:profile"
    )

    with patch("core.connection.memory_utils.create_instance", return_value=provider):
        connection._initialize_memory()

    assert provider.init_memory.call_args.kwargs["save_to_file"] is True


def test_proactive_mode_falls_back_when_device_did_not_negotiate_realtime_aec():
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection.config = {
        "companion": {
            "enabled": True,
            "mode": "proactive",
            "require_realtime_aec": True,
        }
    }
    connection.features = {"aec": False, "realtime": False}
    connection.hello_received = True
    connection.client_aec = False
    connection.logger = Mock()
    connection.emit_debug_event = Mock()
    connection._companion_loop = None

    connection._start_companion_loop()

    assert connection.config["companion"]["mode"] == "turn_based"
    assert connection._companion_loop is None
    connection.emit_debug_event.assert_called_once()


def test_closed_connection_does_not_start_proactive_loop():
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection.config = {
        "companion": {"enabled": True, "mode": "proactive"},
    }
    connection._closed = True
    connection.stop_event = threading.Event()
    connection.stop_event.set()
    connection._companion_loop = None
    connection.emit_debug_event = Mock()
    connection.logger = Mock()
    connection.llm = object()
    connection.tts = object()

    connection._start_companion_loop()

    assert connection._companion_loop is None


def test_turn_boundary_refreshes_api_profile_configuration():
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection.read_config_from_api = True
    connection.device_id = "device-1"
    connection._closed = False
    connection.stop_event = threading.Event()
    connection.loop = None
    connection._initialize_private_config_async = AsyncMock(return_value=True)

    assert connection._refresh_private_config_for_turn() is True
    connection._initialize_private_config_async.assert_awaited_once_with(refresh=True)


@pytest.mark.asyncio
async def test_background_initialization_does_not_submit_after_close():
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection._closed = True
    connection.stop_event = threading.Event()
    connection.stop_event.set()
    connection._initialize_private_config_async = AsyncMock()
    connection.executor = Mock()
    connection.logger = Mock()

    await connection._background_initialize()

    connection._initialize_private_config_async.assert_not_awaited()
    connection.executor.submit.assert_not_called()


@pytest.mark.asyncio
async def test_proactive_start_waits_for_hello_capabilities_before_falling_back():
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection.config = {
        "companion": {
            "enabled": True,
            "mode": "proactive",
            "require_realtime_aec": True,
        }
    }
    connection.features = {}
    connection.hello_received = False
    connection.logger = Mock()
    connection.emit_debug_event = Mock()
    connection._companion_loop = None
    connection.loop = asyncio.get_running_loop()

    connection._start_companion_loop()

    assert connection.config["companion"]["mode"] == "proactive"
    assert connection._companion_loop is None
    pending = getattr(connection, "_companion_start_task", None)
    if pending is not None:
        pending.cancel()
        await asyncio.gather(pending, return_exceptions=True)


@pytest.mark.asyncio
async def test_proactive_start_requests_realtime_listening_from_device():
    connection = ConnectionHandler.__new__(ConnectionHandler)
    connection.config = {"companion": {"enabled": True, "mode": "proactive"}}
    connection.features = {"realtime": True}
    connection.client_aec = False
    connection.llm = object()
    connection.tts = object()
    connection.client_listen_mode = "auto"
    connection.device_id = "device-1"
    connection.logger = Mock()
    connection.emit_debug_event = Mock()
    connection._companion_loop = None
    connection._proactive_context = AsyncMock(return_value={})
    connection._speak_proactive = AsyncMock()
    connection.websocket = SimpleNamespace(send=AsyncMock())

    connection._start_companion_loop()
    await asyncio.sleep(0)
    if connection._companion_loop is not None:
        await connection._companion_loop.stop()

    sent = [call.args[0] for call in connection.websocket.send.await_args_list]
    assert any('"type": "listen"' in value and '"mode": "realtime"' in value for value in sent)


@pytest.mark.asyncio
async def test_recent_user_context_survives_a_normal_assistant_reply():
    connection = proactive_connection()
    connection.dialogue.put(Message(role="user", content="我明天要面试"))
    connection.dialogue.put(Message(role="assistant", content="祝你顺利"))

    context = await connection._proactive_context()

    assert context["has_recent_context"] is True


@pytest.mark.asyncio
async def test_user_context_before_a_proactive_reply_is_not_reused_for_self_talk():
    connection = proactive_connection()
    connection.dialogue.put(Message(role="user", content="我明天要面试"))
    connection.dialogue.put(Message(role="assistant", content="我会陪着你", source="proactive"))

    context = await connection._proactive_context()

    assert context["has_recent_context"] is False


@pytest.mark.asyncio
async def test_memory_confidence_threshold_matches_planner_configuration():
    memory = SimpleNamespace(
        query_memory_candidates=AsyncMock(
            return_value=[{"id": "m1", "content": "低置信资料", "confidence": 0.6}]
        )
    )
    connection = proactive_connection(memory)
    connection.config["companion"]["proactive_min_memory_confidence"] = 0.9

    context = await connection._proactive_context()

    assert context["memory_degraded"] is True


@pytest.mark.asyncio
async def test_healthy_empty_memory_is_not_marked_degraded():
    memory = SimpleNamespace(query_memory_candidates=AsyncMock(return_value=[]))
    connection = proactive_connection(memory)

    context = await connection._proactive_context()

    assert context["memory_degraded"] is False
