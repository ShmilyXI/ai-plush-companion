from __future__ import annotations

import asyncio
import inspect
from collections.abc import Callable, Mapping
from typing import Any

from .proactive_planner import ProactivePlan


class CompanionLoop:
    """Owns idle scheduling and cancellation for one live companion connection."""

    def __init__(self, connection: Any, planner: Any, *, context_provider: Callable[[], Mapping[str, Any]], speaker: Callable[[ProactivePlan], Any]):
        self.connection = connection
        self.planner = planner
        self.context_provider = context_provider
        self.speaker = speaker
        self.task: asyncio.Task | None = None
        self.planning_task: asyncio.Task | None = None
        self.activity_epoch = 0
        self.activity_event = asyncio.Event()
        self.stopped = False

    def start(self) -> None:
        if self.task is None or self.task.done():
            self.stopped = False
            self.task = asyncio.create_task(self._run(), name="companion-loop")

    async def stop(self) -> None:
        self.stopped = True
        for task in (self.planning_task, self.task):
            if task is not None and not task.done():
                task.cancel()
        tasks = [task for task in (self.planning_task, self.task) if task is not None]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self.planning_task = None
        self.task = None

    def notify_activity(self) -> None:
        self.activity_epoch += 1
        self.activity_event.set()
        if self.planning_task is not None and not self.planning_task.done():
            self.planning_task.cancel()

    def _enabled(self) -> bool:
        companion = self.connection.config.get("companion", {})
        return bool(companion.get("enabled")) and companion.get("mode") == "proactive"

    def _idle_delay(self, first: bool) -> float:
        companion = self.connection.config.get("companion", {})
        key = "idle_first_check_seconds" if first else "idle_backoff_min_seconds"
        default = 45.0 if first else 60.0
        try:
            return max(0.1, float(companion.get(key, default)))
        except (TypeError, ValueError):
            return default

    async def _run(self) -> None:
        first = True
        while not self.stopped:
            if not self._enabled():
                await asyncio.sleep(1)
                continue
            self.activity_event.clear()
            try:
                await asyncio.wait_for(self.activity_event.wait(), timeout=self._idle_delay(first))
                first = True
                continue
            except asyncio.TimeoutError:
                pass
            first = False
            if self.stopped or not self._eligible():
                continue
            epoch = self.activity_epoch
            self.planning_task = asyncio.create_task(self._plan(epoch), name="companion-planner")
            try:
                await self.planning_task
            except asyncio.CancelledError:
                if self.stopped:
                    raise
            finally:
                self.planning_task = None

    def _eligible(self) -> bool:
        if not self._enabled():
            return False
        if getattr(self.connection, "client_is_speaking", False):
            return False
        return getattr(self.connection, "client_listen_mode", "realtime") == "realtime"

    async def _plan(self, epoch: int) -> None:
        context_result = self.context_provider()
        if inspect.isawaitable(context_result):
            context_result = await context_result
        context = dict(context_result or {})
        context.setdefault("recent_turns", [])
        context.setdefault("proactive_history", [])
        context.setdefault("memories", [])
        context.setdefault("idle_seconds", int(self._idle_delay(False)))
        plan = await asyncio.to_thread(self.planner.plan, **context)
        logger = getattr(self.connection, "logger", None)
        if logger is not None:
            logger.bind(tag="companion_loop").info(
                f"主动陪伴规划结果: action={plan.action} reason={plan.reason_code} "
                f"chars={len(plan.text or '')} epoch={epoch} current_epoch={self.activity_epoch}"
            )
        if self.stopped or epoch != self.activity_epoch or not self._eligible():
            if logger is not None:
                logger.bind(tag="companion_loop").info(
                    f"主动陪伴规划结果已丢弃: stopped={self.stopped} "
                    f"stale={epoch != self.activity_epoch} eligible={self._eligible()}"
                )
            return
        if plan.action != "speak" or not plan.text:
            return
        runtime = getattr(self.connection, "conversation_runtime", None)
        if runtime is not None:
            from core.conversation.contract import ConversationInput, ConversationRequest

            identity = getattr(self.connection, "companion_identity", None)
            profile_id = str(
                getattr(identity, "agent_id", None)
                or getattr(self.connection, "device_id", None)
                or "device-profile"
            )
            user_id = getattr(identity, "user_id", None)
            conversation_id = str(getattr(self.connection, "session_id", None) or "device-session")
            request = ConversationRequest(
                user_id=user_id,
                profile_id=profile_id,
                conversation_id=conversation_id,
                source="device",
                input_mode="text",
                output_mode="audio",
                capability_bundle=getattr(self.connection, "capability_bundle", None),
            )
            handle = await runtime.start(request)
            await handle.send(
                ConversationInput(
                    kind="text",
                    request_id=f"proactive-{epoch}",
                    text=plan.text,
                )
            )
            await handle.close()
            return
        result = self.speaker(plan)
        if inspect.isawaitable(result):
            await result
