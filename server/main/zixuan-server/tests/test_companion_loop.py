import asyncio
from types import SimpleNamespace

import pytest

from core.companion.companion_loop import CompanionLoop
from core.companion.proactive_planner import ProactivePlan


class Planner:
    def __init__(self, result, started=None, release=None):
        self.result = result
        self.started = started
        self.release = release

    def plan(self, **_context):
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
    loop = CompanionLoop(
        connection(),
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
