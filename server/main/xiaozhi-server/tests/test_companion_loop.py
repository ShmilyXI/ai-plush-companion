import asyncio
import threading
from types import SimpleNamespace

import pytest

from core.companion.companion_loop import CompanionLoop
from core.companion.proactive_planner import ProactivePlan
from core.connection import ConnectionHandler


class Planner:
    def __init__(self, result, started=None, release=None):
        self.result = result
        self.started = started
        self.release = release
        self.calls = 0

    def plan(self, **_context):
        self.calls += 1
        if self.started:
            self.started.set()
        if self.release:
            while not self.release.is_set():
                import time
                time.sleep(0.001)
        return self.result


def connection():
    return SimpleNamespace(
        config={"companion": {"enabled": True, "mode": "proactive", "idle_first_check_seconds": 0.01}},
        client_listen_mode="realtime",
        client_aec=True,
        client_is_speaking=False,
        stop_event=threading_event(),
    )


def threading_event():
    import threading
    return threading.Event()


def test_top_level_chat_holds_connection_gate_for_full_turn():
    entered = threading.Event()
    release = threading.Event()
    connection = SimpleNamespace(
        companion_mutex=threading.Lock(),
        chat_in_progress=False,
        notify_confirmed_user_activity=lambda: None,
    )

    def chat_impl(_query, _depth):
        assert connection.chat_in_progress is True
        entered.set()
        release.wait(timeout=1)
        return "done"

    connection._chat_impl = chat_impl
    worker = threading.Thread(target=ConnectionHandler.chat, args=(connection, "hello"))
    worker.start()
    assert entered.wait(timeout=1)
    assert connection.companion_mutex.acquire(timeout=0.05) is False
    release.set()
    worker.join(timeout=1)

    assert not worker.is_alive()
    assert connection.chat_in_progress is False
    assert connection.companion_mutex.acquire(timeout=0.05) is True
    connection.companion_mutex.release()


@pytest.mark.asyncio
async def test_chat_and_planner_provider_calls_are_serialized():
    in_flight = 0
    max_in_flight = 0
    counter_lock = threading.Lock()
    planner_started = threading.Event()
    planner_release = threading.Event()
    chat_entered = threading.Event()
    chat_release = threading.Event()

    def enter_provider():
        nonlocal in_flight, max_in_flight
        with counter_lock:
            in_flight += 1
            max_in_flight = max(max_in_flight, in_flight)

    def leave_provider():
        nonlocal in_flight
        with counter_lock:
            in_flight -= 1

    class BlockingPlanner:
        def plan(self, **_context):
            enter_provider()
            planner_started.set()
            planner_release.wait(timeout=1)
            leave_provider()
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = SimpleNamespace(
        config={"companion": {"enabled": True, "mode": "proactive"}},
        companion_mutex=threading.Lock(),
        companion_provider_gate=threading.Lock(),
        chat_in_progress=False,
        notify_confirmed_user_activity=lambda: None,
    )

    def chat_impl(_query, _depth):
        enter_provider()
        chat_entered.set()
        chat_release.wait(timeout=1)
        leave_provider()
        return "done"

    conn._chat_impl = chat_impl
    planner = BlockingPlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    planner_task = asyncio.create_task(loop._invoke_planner({}))
    chat_thread = None
    try:
        await asyncio.wait_for(asyncio.to_thread(planner_started.wait), timeout=1)
        chat_thread = threading.Thread(
            target=ConnectionHandler.chat,
            args=(conn, "hello"),
        )
        chat_thread.start()
        await asyncio.sleep(0.05)

        assert not chat_entered.is_set()
        assert max_in_flight == 1

        planner_release.set()
        await planner_task
        chat_release.set()
        chat_thread.join(timeout=1)
        assert not chat_thread.is_alive()
        assert max_in_flight == 1
    finally:
        planner_release.set()
        chat_release.set()
        if not planner_task.done():
            await asyncio.gather(planner_task, return_exceptions=True)
        if chat_thread is not None:
            chat_thread.join(timeout=1)
        await loop.stop()


@pytest.mark.asyncio
async def test_loop_plans_after_idle_and_speaks_only_for_speak_result():
    spoken = []
    loop = CompanionLoop(
        connection(),
        Planner(ProactivePlan("speak", "今天也辛苦了。", "gentle", "comfort")),
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda plan: spoken.append(plan.text),
    )

    loop.start()
    await asyncio.sleep(0.15)
    await loop.stop()

    assert spoken == ["今天也辛苦了。"]


@pytest.mark.asyncio
async def test_activity_cancels_planning_and_drops_stale_result():
    started = asyncio.Event()
    release = asyncio.Event()
    spoken = []
    conn = connection()
    conn.config["companion"]["idle_first_check_seconds"] = 0.1
    loop = CompanionLoop(
        conn,
        Planner(ProactivePlan("speak", "不该播放。", "neutral", "presence"), started, release),
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda plan: spoken.append(plan.text),
    )

    loop.start()
    await asyncio.wait_for(started.wait(), timeout=1)
    loop.notify_activity()
    release.set()
    await asyncio.sleep(0.03)
    await loop.stop()

    assert spoken == []


@pytest.mark.asyncio
async def test_silent_plan_does_not_speak():
    spoken = []
    loop = CompanionLoop(
        connection(),
        Planner(ProactivePlan("silent", "", "neutral", "no_context")),
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda plan: spoken.append(plan.text),
    )

    loop.start()
    await asyncio.sleep(0.04)
    await loop.stop()

    assert spoken == []


@pytest.mark.asyncio
async def test_realtime_device_without_server_aec_can_run_proactive_loop():
    spoken = []
    conn = connection()
    conn.client_aec = False
    loop = CompanionLoop(
        conn,
        Planner(ProactivePlan("speak", "我在呢。", "gentle", "presence")),
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda plan: spoken.append(plan.text),
    )

    loop.start()
    await asyncio.sleep(0.15)
    await loop.stop()

    assert spoken == ["我在呢。"]


@pytest.mark.asyncio
async def test_planner_failure_does_not_kill_loop_and_uses_backoff():
    class FlakyPlanner:
        def __init__(self):
            self.calls = 0

        def plan(self, **_context):
            self.calls += 1
            if self.calls == 1:
                raise RuntimeError("temporary provider failure")
            return ProactivePlan("silent", "", "neutral", "no_context")

    planner = FlakyPlanner()
    conn = connection()
    conn.config["companion"].update({
        "idle_first_check_seconds": 0.01,
        "idle_backoff_min_seconds": 0.01,
        "idle_backoff_max_seconds": 0.03,
        "idle_jitter_seconds": 0,
    })
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda _plan: None,
    )

    loop.start()
    await asyncio.sleep(0.08)
    assert planner.calls >= 2
    assert loop.task is not None and not loop.task.done()
    await loop.stop()


@pytest.mark.asyncio
async def test_millisecond_idle_settings_are_supported_without_fixed_periods():
    planner = Planner(ProactivePlan("silent", "", "neutral", "no_context"))
    conn = connection()
    conn.config["companion"] = {
        "enabled": True,
        "mode": "proactive",
        "idle_first_check_ms": 10,
        "idle_backoff_min_ms": 10,
        "idle_backoff_max_ms": 20,
        "idle_jitter_ms": 0,
    }
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda _plan: None,
    )

    loop.start()
    await asyncio.sleep(0.035)
    await loop.stop()

    assert planner.calls >= 1


@pytest.mark.asyncio
async def test_configured_aec_requirement_falls_back_to_turn_based_capability():
    planner = Planner(ProactivePlan("speak", "不应播放。", "neutral", "presence"))
    conn = connection()
    conn.client_aec = False
    conn.features = {"realtime": False, "aec": False}
    conn.config["companion"]["require_realtime_aec"] = True
    spoken = []
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {"recent_turns": [], "proactive_history": [], "memories": []},
        speaker=lambda plan: spoken.append(plan.text),
    )

    loop.start()
    await asyncio.sleep(0.04)
    await loop.stop()

    assert planner.calls == 0
    assert spoken == []


@pytest.mark.asyncio
async def test_activity_notifies_connection_to_cancel_proactive_playback():
    conn = connection()
    cancellations = []
    conn.cancel_proactive_playback = lambda: cancellations.append(True)
    loop = CompanionLoop(
        conn,
        Planner(ProactivePlan("silent", "", "neutral", "no_context")),
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    loop.playback_active = True

    loop.notify_activity()

    assert cancellations == [True]


@pytest.mark.asyncio
async def test_planner_timeout_does_not_block_user_state_gate():
    class SlowPlanner:
        def plan(self, **_context):
            import time
            time.sleep(0.2)
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = connection()
    conn.config["companion"]["planner_timeout_seconds"] = 0.01
    conn.companion_mutex = threading.Lock()
    loop = CompanionLoop(
        conn,
        SlowPlanner(),
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )

    task = asyncio.create_task(loop._invoke_planner({}))
    await asyncio.sleep(0.02)
    assert conn.companion_mutex.acquire(timeout=0.05) is True
    conn.companion_mutex.release()
    with pytest.raises(asyncio.TimeoutError):
        await task
    await loop.stop()


@pytest.mark.asyncio
async def test_chat_does_not_wait_for_an_uncancellable_planner_provider():
    started = threading.Event()
    release = threading.Event()
    chat_entered = threading.Event()

    class UncancellablePlanner:
        session_id = "connection:planner"

        def plan(self, **_context):
            started.set()
            release.wait(timeout=2)
            return ProactivePlan("silent", "", "neutral", "no_context")

        def cancel(self):
            # Simulate a provider that does not implement cancellation.
            return False

    conn = SimpleNamespace(
        config={
            "companion": {
                "enabled": True,
                "mode": "proactive",
                "planner_timeout_seconds": 0.05,
                "chat_provider_wait_timeout_seconds": 0.05,
            }
        },
        companion_mutex=threading.Lock(),
        _companion_provider_lock=threading.Lock(),
        chat_in_progress=False,
        _closed=False,
        stop_event=threading.Event(),
        notify_confirmed_user_activity=lambda: None,
        clearSpeakStatus=lambda: None,
        emit_debug_event=lambda *args, **kwargs: None,
        logger=SimpleNamespace(bind=lambda **kwargs: SimpleNamespace(
            info=lambda *args, **kwargs: None,
            warning=lambda *args, **kwargs: None,
            error=lambda *args, **kwargs: None,
            debug=lambda *args, **kwargs: None,
        )),
    )

    def chat_impl(_query, _depth=0):
        chat_entered.set()
        return "done"

    conn._chat_impl = chat_impl
    loop = CompanionLoop(
        conn,
        UncancellablePlanner(),
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    planner_task = asyncio.create_task(loop._invoke_planner({}))
    try:
        await asyncio.wait_for(asyncio.to_thread(started.wait), timeout=1)
        chat_task = asyncio.create_task(asyncio.to_thread(ConnectionHandler.chat, conn, "你好"))
        await asyncio.wait_for(chat_task, timeout=0.2)
        assert chat_entered.is_set() is False
        assert conn.chat_in_progress is False
        assert conn._companion_chat_pending is True

        release.set()
        await asyncio.wait_for(asyncio.to_thread(chat_entered.wait), timeout=1)
    finally:
        release.set()
        await asyncio.gather(planner_task, return_exceptions=True)
        await loop.stop()


@pytest.mark.asyncio
async def test_planner_skips_provider_when_chat_owns_connection_gate():
    class CountingPlanner:
        def __init__(self):
            self.calls = 0

        def plan(self, **_context):
            self.calls += 1
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = connection()
    conn.companion_mutex = threading.Lock()
    conn.companion_mutex.acquire()
    conn.chat_in_progress = True
    planner = CountingPlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )

    try:
        await loop._plan(loop.activity_epoch)
    finally:
        conn.chat_in_progress = False
        conn.companion_mutex.release()

    assert planner.calls == 0


@pytest.mark.asyncio
async def test_planner_reservation_wins_race_with_chat_start_after_preflight():
    class CountingPlanner:
        def __init__(self):
            self.calls = 0

        def plan(self, **_context):
            self.calls += 1
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = connection()
    conn.companion_mutex = threading.Lock()
    planner = CountingPlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    checks = 0

    def state_check(epoch):
        nonlocal checks
        checks += 1
        if checks == 2:
            with conn.companion_mutex:
                conn.chat_in_progress = True
        return True

    loop._planner_state_available = state_check
    try:
        await loop._plan(loop.activity_epoch)
    finally:
        conn.chat_in_progress = False

    assert planner.calls == 0


@pytest.mark.asyncio
async def test_chat_and_planner_never_enter_the_same_provider_concurrently():
    entered = threading.Event()
    release = threading.Event()
    active = 0
    peak = 0
    counter_lock = threading.Lock()

    def enter_provider():
        nonlocal active, peak
        with counter_lock:
            active += 1
            peak = max(peak, active)

    def leave_provider():
        nonlocal active
        with counter_lock:
            active -= 1

    class BlockingPlanner:
        def plan(self, **_context):
            enter_provider()
            entered.set()
            release.wait(1)
            leave_provider()
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = connection()
    conn._companion_provider_lock = threading.Lock()
    conn._closed = False
    conn.stop_event = threading.Event()
    conn.config["companion"]["planner_timeout_seconds"] = 1
    loop = CompanionLoop(
        conn,
        BlockingPlanner(),
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    loop.start()
    reservation = loop._reserve_planner_slot(loop.activity_epoch)
    planner_task = asyncio.create_task(loop._invoke_planner({}, reservation=reservation))
    await asyncio.wait_for(asyncio.to_thread(entered.wait), timeout=1)

    chat_entered = threading.Event()

    def fake_chat(_query, _depth=0):
        enter_provider()
        chat_entered.set()
        leave_provider()

    conn._chat_impl = fake_chat

    def run_chat(_query):
        conn._companion_planner_preempt()
        conn.chat_in_progress = True
        try:
            with conn._companion_provider_lock:
                conn._chat_impl(_query)
        finally:
            conn.chat_in_progress = False

    chat_task = asyncio.create_task(asyncio.to_thread(run_chat, "你好"))
    await asyncio.sleep(0.03)
    assert not chat_entered.is_set()
    release.set()
    await asyncio.wait_for(planner_task, timeout=1)
    await asyncio.wait_for(chat_task, timeout=1)
    await loop.stop()

    assert peak == 1


@pytest.mark.asyncio
async def test_chat_preempts_an_active_planner_without_reservation():
    planner_started = threading.Event()
    planner_cancelled = threading.Event()
    chat_entered = threading.Event()

    class CancellablePlanner:
        def plan(self, **_context):
            planner_started.set()
            planner_cancelled.wait(timeout=1)
            return ProactivePlan("silent", "", "neutral", "no_context")

        def cancel(self):
            planner_cancelled.set()

    conn = connection()
    conn._companion_provider_lock = threading.Lock()
    conn._closed = False
    conn.stop_event = threading.Event()
    conn.notify_confirmed_user_activity = lambda: None

    def fake_chat(_query, _depth=0):
        chat_entered.set()

    conn._chat_impl = fake_chat
    planner = CancellablePlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    loop.start()
    planner_task = asyncio.create_task(loop._invoke_planner({}))
    chat_thread = None
    try:
        await asyncio.wait_for(asyncio.to_thread(planner_started.wait), timeout=1)
        chat_thread = threading.Thread(
            target=ConnectionHandler.chat,
            args=(conn, "你好"),
        )
        chat_thread.start()

        assert planner_cancelled.wait(timeout=0.2)
        await asyncio.wait_for(planner_task, timeout=1)
        chat_thread.join(timeout=1)
        assert not chat_thread.is_alive()
        assert chat_entered.is_set()
    finally:
        planner_cancelled.set()
        if not planner_task.done():
            await asyncio.gather(planner_task, return_exceptions=True)


@pytest.mark.asyncio
async def test_queued_chat_expires_when_provider_gate_stays_busy():
    events = []
    provider_lock = threading.Lock()
    provider_lock.acquire()

    conn = SimpleNamespace(
        config={
            "companion": {
                "enabled": True,
                "mode": "proactive",
                "chat_provider_wait_timeout_seconds": 0.01,
                "chat_queue_timeout_seconds": 0.2,
            }
        },
        companion_mutex=threading.Lock(),
        _companion_provider_lock=provider_lock,
        chat_in_progress=False,
        _closed=False,
        stop_event=threading.Event(),
        notify_confirmed_user_activity=lambda: None,
        clearSpeakStatus=lambda: None,
        emit_debug_event=lambda *args, **kwargs: events.append((args, kwargs)),
        logger=SimpleNamespace(
            bind=lambda **kwargs: SimpleNamespace(
                info=lambda *args, **kwargs: None,
                warning=lambda *args, **kwargs: None,
                error=lambda *args, **kwargs: None,
                debug=lambda *args, **kwargs: None,
            )
        ),
    )
    conn._chat_impl = lambda _query, _depth=0: "unexpected"

    try:
        result = await asyncio.to_thread(ConnectionHandler.chat, conn, "你好")
        assert result is None
        assert conn._companion_chat_pending is True

        await asyncio.sleep(0.45)

        assert conn._companion_chat_pending is False
        assert conn.chat_in_progress is False
        assert list(conn._companion_chat_queue) == []
        assert any(
            args[1] == "conversation.chat_provider_busy"
            and kwargs.get("details", {}).get("queued") is False
            for args, kwargs in events
        )
    finally:
        conn._closed = True
        conn.stop_event.set()
        provider_lock.release()
        worker = getattr(conn, "_companion_chat_dispatch_thread", None)
        if worker is not None and worker.is_alive():
            await asyncio.to_thread(worker.join, 1)


@pytest.mark.asyncio
async def test_queued_chat_worker_does_not_hold_state_gate_while_waiting_for_provider():
    provider_lock = threading.Lock()
    provider_lock.acquire()
    second_chat_returned = threading.Event()

    conn = SimpleNamespace(
        config={
            "companion": {
                "enabled": True,
                "mode": "proactive",
                "chat_provider_wait_timeout_seconds": 0.01,
                "chat_queue_timeout_seconds": 0.5,
            }
        },
        companion_mutex=threading.Lock(),
        _companion_provider_lock=provider_lock,
        chat_in_progress=False,
        _closed=False,
        stop_event=threading.Event(),
        notify_confirmed_user_activity=lambda: None,
        clearSpeakStatus=lambda: None,
        emit_debug_event=lambda *args, **kwargs: None,
        logger=SimpleNamespace(
            bind=lambda **kwargs: SimpleNamespace(
                info=lambda *args, **kwargs: None,
                warning=lambda *args, **kwargs: None,
                error=lambda *args, **kwargs: None,
                debug=lambda *args, **kwargs: None,
            )
        ),
    )
    conn._chat_impl = lambda _query, _depth=0: "done"

    try:
        assert await asyncio.to_thread(ConnectionHandler.chat, conn, "第一条") is None
        await asyncio.sleep(0.03)

        def run_second_chat():
            ConnectionHandler.chat(conn, "第二条")
            second_chat_returned.set()

        second_thread = threading.Thread(target=run_second_chat)
        second_thread.start()
        assert await asyncio.to_thread(second_chat_returned.wait, 0.35)
        second_thread.join(timeout=1)
        assert not second_thread.is_alive()
    finally:
        conn._closed = True
        conn.stop_event.set()
        provider_lock.release()
        worker = getattr(conn, "_companion_chat_dispatch_thread", None)
        if worker is not None and worker.is_alive():
            await asyncio.to_thread(worker.join, 1)


@pytest.mark.asyncio
async def test_planner_rechecks_gate_after_context_yields_to_chat():
    context_started = asyncio.Event()
    release_context = asyncio.Event()

    class CountingPlanner:
        def __init__(self):
            self.calls = 0

        def plan(self, **_context):
            self.calls += 1
            return ProactivePlan("silent", "", "neutral", "no_context")

    async def context_provider():
        context_started.set()
        await release_context.wait()
        return {}

    conn = connection()
    conn.companion_mutex = threading.Lock()
    planner = CountingPlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=context_provider,
        speaker=lambda _plan: None,
    )
    request = asyncio.create_task(loop._plan(loop.activity_epoch))
    await context_started.wait()
    conn.companion_mutex.acquire()
    conn.chat_in_progress = True
    release_context.set()

    try:
        await request
    finally:
        conn.chat_in_progress = False
        conn.companion_mutex.release()

    assert planner.calls == 0


@pytest.mark.asyncio
async def test_planner_skips_provider_for_closed_connection():
    class CountingPlanner:
        def __init__(self):
            self.calls = 0

        def plan(self, **_context):
            self.calls += 1
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = connection()
    conn._closed = True
    planner = CountingPlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )

    await loop._plan(loop.activity_epoch)

    assert planner.calls == 0


@pytest.mark.asyncio
async def test_planner_timeout_notifies_provider_and_cleans_request_slot():
    started = threading.Event()
    cancelled = threading.Event()
    finished = threading.Event()

    class CancellablePlanner:
        def __init__(self):
            self.cancel_calls = 0

        def cancel(self):
            self.cancel_calls += 1
            cancelled.set()

        def plan(self, **_context):
            started.set()
            while not cancelled.is_set():
                import time

                time.sleep(0.001)
            finished.set()
            return ProactivePlan("silent", "", "neutral", "no_context")

    conn = connection()
    conn.config["companion"]["planner_timeout_seconds"] = 0.1
    planner = CancellablePlanner()
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    request = asyncio.create_task(loop._invoke_planner({}))
    await asyncio.wait_for(asyncio.to_thread(started.wait), timeout=1)

    try:
        with pytest.raises(asyncio.TimeoutError):
            await request
        assert planner.cancel_calls == 1
        await asyncio.wait_for(asyncio.to_thread(finished.wait), timeout=1)
        await asyncio.sleep(0)
        assert loop._planner_future is None
    finally:
        cancelled.set()
        await loop.stop()


@pytest.mark.asyncio
async def test_each_planner_request_gets_a_fresh_provider_session_id():
    class RecordingPlanner:
        session_id = "connection:planner"

        def __init__(self):
            self.ids = []

        def plan(self, **context):
            self.ids.append(context.get("request_session_id"))
            return ProactivePlan("silent", "", "neutral", "no_context")

    planner = RecordingPlanner()
    loop = CompanionLoop(
        connection(),
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )

    await loop._invoke_planner({})
    await loop._invoke_planner({})

    assert len(planner.ids) == 2
    assert planner.ids[0] != planner.ids[1]


@pytest.mark.asyncio
async def test_timeout_does_not_queue_unbounded_planner_workers():
    started = threading.Event()
    release = threading.Event()

    class SlowPlanner:
        def __init__(self):
            self.calls = 0

        def plan(self, **_context):
            self.calls += 1
            started.set()
            while not release.is_set():
                import time
                time.sleep(0.001)
            return ProactivePlan("silent", "", "neutral", "no_context")

    planner = SlowPlanner()
    conn = connection()
    conn.config["companion"]["planner_timeout_seconds"] = 0.01
    loop = CompanionLoop(
        conn,
        planner,
        context_provider=lambda: {},
        speaker=lambda _plan: None,
    )
    loop.start()
    await asyncio.wait_for(asyncio.to_thread(started.wait), timeout=1)
    await asyncio.sleep(0.04)
    assert planner.calls == 1
    release.set()
    await loop.stop()
