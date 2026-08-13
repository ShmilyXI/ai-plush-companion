import asyncio
import threading
import time
from unittest.mock import AsyncMock

import pytest

from config.manage_api_client import (
    ManageApiClient,
    close_current_async_client,
    report_debug_event,
)
from core.debug_events import DebugEventReporter


def emit_event(reporter, **overrides):
    values = {
        "sentence_id": "sentence-a",
        "category": "conversation",
        "event_type": "conversation.completed",
        "level": "info",
        "summary": "completed",
        "details": {"turn": 2},
        "occurred_at": 123,
        "duration_ms": 45,
    }
    values.update(overrides)
    return reporter.emit(
        values.pop("category"),
        values.pop("event_type"),
        values.pop("level"),
        values.pop("summary"),
        **values,
    )


def test_emit_builds_the_stable_payload_without_sensitive_top_level_fields():
    sent = []
    details = {"messages": ["left inside details for server sanitization"]}
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    try:
        assert emit_event(reporter, details=details, occurred_at=0)
        assert reporter.flush_for_test(timeout=1)

        assert sent == [
            {
                "deviceRef": "device-a",
                "sessionId": "session-a",
                "sentenceId": "sentence-a",
                "category": "conversation",
                "eventType": "conversation.completed",
                "level": "info",
                "summary": "completed",
                "details": details,
                "occurredAt": 0,
                "durationMs": 45,
            }
        ]
        assert sent[0]["details"] is not details
        assert not {
            "thinking",
            "thoughts",
            "reasoning",
            "systemPrompt",
            "prompt",
            "messages",
            "rawAudio",
            "audio",
        }.intersection(sent[0])
    finally:
        reporter.close()


def test_emit_snapshots_nested_details_before_returning():
    sender_entered = threading.Event()
    release_sender = threading.Event()
    sent = []

    def controlled_sender(payload):
        sender_entered.set()
        release_sender.wait()
        sent.append(payload)

    details = {"nested": {"items": ["original"]}}
    reporter = DebugEventReporter("device-a", "session-a", sender=controlled_sender)
    try:
        assert emit_event(reporter, details=details)
        assert sender_entered.wait(1)
        details["nested"]["items"].append("mutated")
        release_sender.set()
        assert reporter.flush_for_test(timeout=1)
        assert sent[0]["details"] == {"nested": {"items": ["original"]}}
    finally:
        release_sender.set()
        reporter.close()


def test_emit_returns_false_when_details_cannot_be_copied():
    class Uncopyable:
        def __deepcopy__(self, memo):
            raise TypeError("cannot copy")

    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    try:
        assert not emit_event(reporter, details={"value": Uncopyable()})
        assert reporter.flush_for_test(timeout=1)
        assert sent == []
    finally:
        reporter.close()


@pytest.mark.parametrize("queue_size", [0, -1])
def test_reporter_rejects_nonpositive_queue_size(queue_size):
    with pytest.raises(ValueError):
        DebugEventReporter("device-a", "session-a", queue_size=queue_size)


def test_emit_defaults_details_and_occurred_at(monkeypatch):
    sent = []
    monkeypatch.setattr("core.debug_events.time.time", lambda: 1234.5678)
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    try:
        assert emit_event(reporter, details=None, occurred_at=None)
        assert reporter.flush_for_test(timeout=1)
        assert sent[0]["details"] == {}
        assert sent[0]["occurredAt"] == 1234567
    finally:
        reporter.close()


@pytest.mark.parametrize("category", ["conversation", "model_tool", "audio", "device"])
def test_emit_accepts_supported_categories(category):
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    try:
        assert emit_event(reporter, category=category)
        assert reporter.flush_for_test(timeout=1)
        assert sent[0]["category"] == category
    finally:
        reporter.close()


@pytest.mark.parametrize("level", ["debug", "info", "warning", "error"])
def test_emit_accepts_supported_levels(level):
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    try:
        assert emit_event(reporter, level=level)
        assert reporter.flush_for_test(timeout=1)
        assert sent[0]["level"] == level
    finally:
        reporter.close()


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("category", "system"),
        ("level", "fatal"),
        ("event_type", "invalid"),
        ("event_type", "Conversation.completed"),
        ("event_type", "conversation..completed"),
    ],
)
def test_invalid_contract_values_are_rejected_without_enqueueing(field, value):
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    try:
        assert not emit_event(reporter, **{field: value})
        assert reporter.flush_for_test(timeout=1)
        assert sent == []
    finally:
        reporter.close()


def test_emit_does_not_accept_dangerous_top_level_fields():
    reporter = DebugEventReporter("device-a", "session-a", sender=lambda payload: None)
    try:
        with pytest.raises(TypeError):
            reporter.emit(
                "conversation",
                "conversation.completed",
                "info",
                "completed",
                messages=[{"role": "system", "content": "secret"}],
            )
    finally:
        reporter.close()


def test_queue_full_returns_false_without_waiting_for_sender():
    sender_entered = threading.Event()
    release_sender = threading.Event()

    def slow_sender(payload):
        sender_entered.set()
        release_sender.wait()

    reporter = DebugEventReporter(
        "device-a", "session-a", sender=slow_sender, queue_size=1
    )
    try:
        assert emit_event(reporter, summary="being sent")
        assert sender_entered.wait(1)
        assert emit_event(reporter, summary="queued")

        started = time.monotonic()
        assert not emit_event(reporter, summary="dropped")
        assert time.monotonic() - started < 0.1
    finally:
        release_sender.set()
        assert reporter.flush_for_test(timeout=1)
        reporter.close()


def test_close_stops_new_events_and_does_not_wait_for_slow_sender():
    sender_entered = threading.Event()
    release_sender = threading.Event()

    def slow_sender(payload):
        sender_entered.set()
        release_sender.wait()

    reporter = DebugEventReporter("device-a", "session-a", sender=slow_sender)
    try:
        assert emit_event(reporter)
        assert sender_entered.wait(1)

        close_call = threading.Thread(target=reporter.close)
        close_call.start()
        close_call.join(0.1)
        assert not close_call.is_alive()
        assert not emit_event(reporter, summary="after close")
    finally:
        release_sender.set()
        assert reporter.flush_for_test(timeout=1)
        reporter.close()


def test_close_linearizes_with_an_emit_already_inside_the_state_lock():
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    emit_holds_lock = threading.Event()
    release_emit = threading.Event()
    original_put_nowait = reporter._queue.put_nowait
    put_count = 0
    put_count_lock = threading.Lock()

    def controlled_put(payload):
        nonlocal put_count
        with put_count_lock:
            put_count += 1
            current_put = put_count
        if current_put == 1:
            emit_holds_lock.set()
            assert release_emit.wait(1)
        original_put_nowait(payload)

    reporter._queue.put_nowait = controlled_put
    emit_result = []
    emit_call = threading.Thread(target=lambda: emit_result.append(emit_event(reporter)))
    close_call = threading.Thread(target=reporter.close)

    emit_call.start()
    assert emit_holds_lock.wait(1)
    close_call.start()
    close_call.join(0.1)
    try:
        assert close_call.is_alive()
    finally:
        release_emit.set()
        emit_call.join(1)
        close_call.join(1)

    assert emit_result == [True]
    assert not emit_event(reporter, summary="after close")
    assert reporter.flush_for_test(timeout=1)
    assert [payload["summary"] for payload in sent] == ["completed"]


def test_emit_waiting_behind_close_returns_false():
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    close_holds_lock = threading.Event()
    release_close = threading.Event()
    original_put_nowait = reporter._queue.put_nowait

    def controlled_put(payload):
        close_holds_lock.set()
        assert release_close.wait(1)
        original_put_nowait(payload)

    reporter._queue.put_nowait = controlled_put
    close_call = threading.Thread(target=reporter.close)
    emit_result = []
    emit_call = threading.Thread(target=lambda: emit_result.append(emit_event(reporter)))

    close_call.start()
    assert close_holds_lock.wait(1)
    emit_call.start()
    try:
        assert emit_call.is_alive()
    finally:
        release_close.set()
        close_call.join(1)
        emit_call.join(1)

    assert emit_result == [False]
    assert reporter.flush_for_test(timeout=1)
    assert sent == []


def test_worker_cannot_exit_between_close_stop_and_sentinel_enqueue():
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    close_holds_lock = threading.Event()
    release_close = threading.Event()
    original_put_nowait = reporter._queue.put_nowait

    def controlled_put(payload):
        close_holds_lock.set()
        assert release_close.wait(1)
        original_put_nowait(payload)

    reporter._queue.put_nowait = controlled_put
    close_call = threading.Thread(target=reporter.close)
    close_call.start()
    assert close_holds_lock.wait(1)
    try:
        reporter._thread.join(0.2)
        assert reporter._thread.is_alive()
    finally:
        release_close.set()
        close_call.join(1)

    assert reporter.flush_for_test(timeout=1)
    reporter._thread.join(1)
    assert not reporter._thread.is_alive()


def test_sender_failure_is_swallowed_and_worker_processes_next_event():
    sent = []

    def flaky_sender(payload):
        sent.append(payload["summary"])
        if len(sent) == 1:
            raise RuntimeError("network failed")

    reporter = DebugEventReporter("device-a", "session-a", sender=flaky_sender)
    try:
        assert emit_event(reporter, summary="first")
        assert emit_event(reporter, summary="second")
        assert reporter.flush_for_test(timeout=1)
        assert sent == ["first", "second"]
    finally:
        reporter.close()


def test_flush_for_test_times_out_then_completes_without_sleeping():
    sender_entered = threading.Event()
    release_sender = threading.Event()

    def slow_sender(payload):
        sender_entered.set()
        release_sender.wait()

    reporter = DebugEventReporter("device-a", "session-a", sender=slow_sender)
    try:
        assert emit_event(reporter)
        assert sender_entered.wait(1)
        assert not reporter.flush_for_test(timeout=0.01)
        release_sender.set()
        assert reporter.flush_for_test(timeout=1)
    finally:
        release_sender.set()
        reporter.close()


def test_worker_awaits_an_async_sender():
    sent = []

    async def async_sender(payload):
        await asyncio.sleep(0)
        sent.append(payload["summary"])

    reporter = DebugEventReporter("device-a", "session-a", sender=async_sender)
    try:
        assert emit_event(reporter)
        assert reporter.flush_for_test(timeout=1)
        assert sent == ["completed"]
    finally:
        reporter.close()


def test_close_wakes_an_idle_worker():
    reporter = DebugEventReporter("device-a", "session-a", sender=lambda payload: None)
    reporter.close()
    reporter._thread.join(0.2)
    assert not reporter._thread.is_alive()


def test_close_is_idempotent_and_does_not_add_unfinished_work():
    reporter = DebugEventReporter("device-a", "session-a", sender=lambda payload: None)
    reporter.close()
    unfinished_after_first_close = reporter._queue.unfinished_tasks
    reporter.close()

    assert reporter._queue.unfinished_tasks == unfinished_after_first_close
    assert reporter.flush_for_test(timeout=1)
    reporter._thread.join(0.2)
    assert not reporter._thread.is_alive()


def test_worker_reuses_one_event_loop_and_closes_it(monkeypatch):
    loop_ids = []

    async def async_sender(payload):
        loop_ids.append(id(asyncio.get_running_loop()))

    closed = threading.Event()

    async def fake_close_current_async_client():
        closed.set()

    monkeypatch.setattr(
        "core.debug_events.close_current_async_client", fake_close_current_async_client
    )
    reporter = DebugEventReporter("device-a", "session-a", sender=async_sender)
    assert emit_event(reporter, summary="first")
    assert emit_event(reporter, summary="second")
    assert reporter.flush_for_test(timeout=1)
    reporter.close()
    reporter._thread.join(1)

    assert len(loop_ids) == 2
    assert len(set(loop_ids)) == 1
    assert closed.is_set()
    assert not reporter._thread.is_alive()


def test_report_debug_event_returns_none_without_manage_api_client(monkeypatch):
    monkeypatch.setattr(ManageApiClient, "_instance", None)
    assert asyncio.run(report_debug_event({"deviceRef": "device-a"})) is None


def test_report_debug_event_posts_to_internal_endpoint(monkeypatch):
    calls = []

    class FakeClient:
        async def _execute_async_request(self, method, endpoint, **kwargs):
            calls.append((method, endpoint, kwargs))
            return {"accepted": True}

    monkeypatch.setattr(ManageApiClient, "_instance", FakeClient())
    payload = {"deviceRef": "device-a"}

    result = asyncio.run(report_debug_event(payload))

    assert result == {"accepted": True}
    assert calls == [
        (
            "POST",
            "/internal/device-debug-logs/events",
            {"json": payload},
        )
    ]


def test_close_current_async_client_only_closes_the_running_loops_client(monkeypatch):
    current_loop = asyncio.new_event_loop()
    other_client = object()
    current_client = AsyncMock()
    monkeypatch.setattr(
        ManageApiClient,
        "_async_clients",
        {id(current_loop): current_client, 999: other_client},
    )

    current_loop.run_until_complete(close_current_async_client())
    current_loop.close()

    current_client.aclose.assert_awaited_once_with()
    assert ManageApiClient._async_clients == {999: other_client}
