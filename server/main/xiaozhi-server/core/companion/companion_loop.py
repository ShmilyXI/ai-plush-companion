from __future__ import annotations

import asyncio
import inspect
import random
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from collections.abc import Callable, Mapping
from typing import Any

from .proactive_planner import ProactivePlan


class _PlannerBusy(RuntimeError):
    pass


class _PlannerPreempted(RuntimeError):
    pass


class CompanionLoop:
    """Own idle scheduling and cancellation for one live companion connection."""

    def __init__(
        self,
        connection: Any,
        planner: Any,
        *,
        context_provider: Callable[[], Mapping[str, Any]],
        speaker: Callable[[ProactivePlan], Any],
        random_fn: Callable[[], float] | None = None,
    ):
        self.connection = connection
        self.planner = planner
        self.context_provider = context_provider
        self.speaker = speaker
        self.random_fn = random_fn or random.random
        self.task: asyncio.Task | None = None
        self.planning_task: asyncio.Task | None = None
        self.activity_epoch = 0
        self.activity_event = asyncio.Event()
        self.stopped = False
        self.state = "stopped"
        self.playback_active = False
        self._backoff_seconds: float | None = None
        self._event_loop: asyncio.AbstractEventLoop | None = None
        self._planner_executor: ThreadPoolExecutor | None = None
        self._planner_future = None
        self._planner_cancel_notified = False
        self._planner_reservation = None
        self._planner_reservation_lock = threading.Lock()
        self.unsupported_reason: str | None = None

    def start(self) -> None:
        if self.task is None or self.task.done():
            if self._planner_executor is not None:
                self._planner_executor.shutdown(wait=False, cancel_futures=True)
            self.stopped = False
            self.state = "idle_wait"
            self._backoff_seconds = None
            self._event_loop = asyncio.get_running_loop()
            self._planner_executor = ThreadPoolExecutor(
                max_workers=1, thread_name_prefix="companion-planner"
            )
            setattr(self.connection, "_companion_planner_preempt", self.preempt_for_chat)
            self.task = asyncio.create_task(self._run(), name="companion-loop")

    async def stop(self) -> None:
        self.stopped = True
        self.state = "stopped"
        current = asyncio.current_task()
        tasks = [
            task
            for task in (self.planning_task, self.task)
            if task is not None and task is not current and not task.done()
        ]
        for task in tasks:
            task.cancel()
        self._cancel_planner_request()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self.planning_task = None
        self.task = None
        self.playback_active = False
        executor, self._planner_executor = self._planner_executor, None
        if executor is not None:
            executor.shutdown(wait=False, cancel_futures=True)
        self._planner_future = None
        callback = getattr(self.connection, "_companion_planner_preempt", None)
        if getattr(callback, "__self__", None) is self:
            setattr(self.connection, "_companion_planner_preempt", None)
        with self._planner_reservation_lock:
            self._planner_reservation = None

    def notify_activity(self) -> None:
        """Invalidate stale plans and stop proactive playback on user activity."""
        self.activity_epoch += 1
        self._backoff_seconds = None
        loop = self._event_loop
        try:
            current_loop = asyncio.get_running_loop()
        except RuntimeError:
            current_loop = None
        if loop is not None and loop.is_running() and loop is not current_loop:
            loop.call_soon_threadsafe(self._notify_activity_on_loop)
        else:
            self._notify_activity_on_loop()

    def _notify_activity_on_loop(self) -> None:
        self.activity_event.set()
        if self.planning_task is not None and not self.planning_task.done():
            self.planning_task.cancel()
        if self.playback_active or getattr(self.connection, "proactive_playback_active", False):
            self._cancel_playback()

    def _cancel_playback(self) -> None:
        callback = getattr(self.connection, "cancel_proactive_playback", None)
        if not callable(callback):
            return
        try:
            result = callback()
            if inspect.isawaitable(result):
                asyncio.create_task(result)
        except Exception as error:
            self._log("主动陪伴播放取消失败", error)

    def _enabled(self) -> bool:
        companion = self.connection.config.get("companion", {})
        return bool(companion.get("enabled")) and companion.get("mode") == "proactive"

    def _requires_realtime_aec(self) -> bool:
        companion = self.connection.config.get("companion", {})
        return bool(companion.get("require_realtime_aec", False))

    def _supports_realtime_aec(self) -> bool:
        if not self._requires_realtime_aec():
            return True
        features = getattr(self.connection, "features", None)
        if not isinstance(features, Mapping):
            features = {}
        return bool(
            getattr(self.connection, "client_aec", False)
            or features.get("aec")
            or features.get("realtime")
        )

    def _idle_delay(self, first: bool) -> float:
        companion = self.connection.config.get("companion", {})
        if first:
            milliseconds = companion.get("idle_first_check_ms")
            seconds = companion.get("idle_first_check_seconds", 45.0)
        else:
            milliseconds = None
            seconds = self._backoff_seconds
            if seconds is None:
                seconds = companion.get("idle_backoff_min_seconds", 60.0)
        if milliseconds is not None:
            try:
                return max(0.01, float(milliseconds) / 1000.0)
            except (TypeError, ValueError):
                pass
        try:
            return max(0.01, float(seconds))
        except (TypeError, ValueError):
            return 45.0 if first else 60.0

    def _next_backoff(self) -> float:
        companion = self.connection.config.get("companion", {})
        minimum = self._number(companion, "idle_backoff_min_ms", "idle_backoff_min_seconds", 60.0, 1000.0)
        maximum = self._number(companion, "idle_backoff_max_ms", "idle_backoff_max_seconds", 900.0, 1000.0)
        minimum = max(0.01, minimum)
        maximum = max(minimum, maximum)
        current = self._backoff_seconds
        base = minimum if current is None else min(maximum, max(minimum, current * 2.0))
        jitter = self._number(companion, "idle_jitter_ms", "idle_jitter_seconds", 0.0, 1000.0)
        if jitter > 0:
            base += (self.random_fn() * 2.0 - 1.0) * jitter
        self._backoff_seconds = max(0.01, min(maximum + jitter, base))
        return self._backoff_seconds

    @staticmethod
    def _number(companion: Mapping[str, Any], ms_key: str, seconds_key: str, default: float, scale: float) -> float:
        value = companion.get(ms_key)
        if value is not None:
            try:
                return float(value) / scale
            except (TypeError, ValueError):
                pass
        try:
            return float(companion.get(seconds_key, default))
        except (TypeError, ValueError):
            return default

    async def _run(self) -> None:
        first = True
        while not self.stopped:
            if not self._enabled():
                self.state = "disabled"
                await asyncio.sleep(1)
                continue
            if not self._supports_realtime_aec():
                self.state = "unsupported"
                self.unsupported_reason = "realtime_aec_required"
                self._log("主动陪伴设备不支持 realtime/AEC，回退对答模式")
                return
            self.state = "idle_wait"
            self.activity_event.clear()
            try:
                await asyncio.wait_for(self.activity_event.wait(), timeout=self._idle_delay(first))
                first = True
                continue
            except asyncio.TimeoutError:
                pass
            if self.stopped:
                break
            if not self._eligible():
                first = False
                self._next_backoff()
                continue
            epoch = self.activity_epoch
            self.state = "planning"
            self._emit_event("companion.planner_started", "主动陪伴规划开始", {"epoch": epoch})
            self.planning_task = asyncio.create_task(self._plan(epoch), name="companion-planner")
            try:
                await self.planning_task
            except asyncio.CancelledError:
                self._emit_event("companion.planner_cancelled", "主动陪伴规划已取消", {"epoch": epoch})
                if self.stopped:
                    raise
            except Exception as error:
                if isinstance(error, _PlannerBusy):
                    self._emit_event("companion.planner_busy", "主动陪伴规划仍在处理中", {})
                    continue
                self._emit_event(
                    "companion.planner_failed",
                    "主动陪伴规划失败",
                    {"errorClass": type(error).__name__},
                )
                self._log("主动陪伴规划失败", error)
            finally:
                self.planning_task = None
                if not self.stopped:
                    if epoch == self.activity_epoch:
                        self._next_backoff()
            first = epoch != self.activity_epoch

    def _eligible(self) -> bool:
        if not self._enabled() or not self._supports_realtime_aec():
            return False
        if self._chat_busy():
            return False
        if getattr(self.connection, "client_is_speaking", False):
            return False
        listen_mode = getattr(self.connection, "client_listen_mode", "realtime")
        if self._requires_realtime_aec():
            return listen_mode == "realtime"
        return listen_mode in {"realtime", "auto"}

    async def _plan(self, epoch: int) -> None:
        if not self._planner_state_available(epoch):
            self._emit_event("companion.planner_skipped", "普通对话正在进行，跳过主动规划", {})
            return
        context_result = self.context_provider()
        if inspect.isawaitable(context_result):
            context_result = await context_result
        context = dict(context_result or {})
        if context.pop("state_busy", False):
            self._emit_event("companion.planner_skipped", "普通对话正在进行，跳过主动规划", {})
            return
        context.setdefault("recent_turns", [])
        context.setdefault("proactive_history", [])
        context.setdefault("memories", [])
        context.setdefault("idle_seconds", int(self._idle_delay(False)))

        # Memory lookup may yield to a user chat. Recheck immediately before
        # touching the shared LLM provider; a stale snapshot is discarded
        # instead of starting another provider request during the chat turn.
        if not self._planner_state_available(epoch):
            self._emit_event("companion.planner_skipped", "普通对话正在进行，跳过主动规划", {})
            return
        reservation = self._reserve_planner_slot(epoch)
        if reservation is None:
            self._emit_event("companion.planner_skipped", "普通对话正在进行，跳过主动规划", {})
            return
        try:
            plan = await self._invoke_planner(context, reservation=reservation)
        except _PlannerPreempted:
            self._release_planner_reservation(reservation)
            return
        except BaseException:
            if self._planner_future is None or self._planner_future.done():
                self._release_planner_reservation(reservation)
            raise
        if self._planner_future is None or self._planner_future.done():
            self._release_planner_reservation(reservation)
        if isinstance(plan, _PlannerPreempted):
            self._emit_event("companion.planner_skipped", "普通对话正在进行，跳过主动规划", {})
            return
        if not isinstance(plan, ProactivePlan):
            self._log("主动陪伴规划返回无效结果")
            return
        self._log_plan(plan, epoch)
        state_lock = getattr(self.connection, "companion_mutex", None)
        acquired = state_lock.acquire(False) if state_lock is not None else True
        if not acquired:
            stale = True
        else:
            try:
                stale = (
                    self.stopped
                    or epoch != self.activity_epoch
                    or not self._eligible()
                    or self._chat_busy()
                )
            finally:
                if state_lock is not None:
                    state_lock.release()
        if stale:
            self._log("主动陪伴规划结果已丢弃")
            self._emit_event("companion.planner_discarded", "主动陪伴规划结果已丢弃", {"epoch": epoch})
            return
        if plan.action != "speak" or not plan.text:
            return
        self.playback_active = True
        self.state = "playing"
        try:
            result = self.speaker(plan)
            if inspect.isawaitable(result):
                await result
        except asyncio.CancelledError:
            self._cancel_playback()
            raise
        except Exception as error:
            self._log("主动陪伴播放失败", error)
            self._cancel_playback()
        finally:
            self.playback_active = False
            if not self.stopped:
                self.state = "idle_wait"

    def _planner_state_available(self, epoch: int) -> bool:
        """Check the short connection gate without waiting on user chat."""
        if self.stopped or epoch != self.activity_epoch or self._connection_closed():
            return False
        state_lock = getattr(self.connection, "companion_mutex", None)
        if state_lock is None or not hasattr(state_lock, "acquire"):
            return not (
                self._chat_busy()
                or self._connection_closed()
            )
        if not state_lock.acquire(False):
            return False
        try:
            return not (
                self.stopped
                or epoch != self.activity_epoch
                or self._chat_busy()
                or self._connection_closed()
            )
        finally:
            state_lock.release()

    def _connection_closed(self) -> bool:
        if bool(getattr(self.connection, "_closed", False)):
            return True
        stop_event = getattr(self.connection, "stop_event", None)
        return bool(stop_event is not None and stop_event.is_set())

    def _chat_busy(self) -> bool:
        """Treat deferred and actively running user turns as reserved."""
        return bool(
            getattr(self.connection, "chat_in_progress", False)
            or getattr(self.connection, "_companion_chat_pending", False)
        )

    def _provider_lock(self):
        """Resolve the single provider gate shared with ConnectionHandler."""
        getter = getattr(self.connection, "_get_companion_provider_lock", None)
        if callable(getter):
            return getter()
        provider_lock = getattr(self.connection, "_companion_provider_lock", None)
        if provider_lock is None:
            provider_lock = getattr(self.connection, "companion_provider_gate", None)
        if provider_lock is None:
            provider_lock = threading.Lock()
        # Keep both names as aliases when a lightweight test/integration
        # connection does not provide the production helper.
        self.connection._companion_provider_lock = provider_lock
        self.connection.companion_provider_gate = provider_lock
        return provider_lock

    def _reserve_planner_slot(self, epoch: int):
        """Atomically reserve the provider hand-off before a worker is queued."""
        if not self._planner_state_available(epoch):
            return None
        state_lock = getattr(self.connection, "companion_mutex", None)
        if state_lock is None or not hasattr(state_lock, "acquire"):
            if self._chat_busy():
                return None
            lock_acquired = False
        else:
            if not state_lock.acquire(False):
                return None
            lock_acquired = True
        try:
            if (
                self.stopped
                or epoch != self.activity_epoch
                or self._connection_closed()
                or self._chat_busy()
            ):
                return None
            reservation = {
                "token": uuid.uuid4().hex,
                "cancelled": threading.Event(),
                "started": threading.Event(),
            }
            with self._planner_reservation_lock:
                if self._planner_reservation is not None:
                    return None
                self._planner_reservation = reservation
            return reservation
        finally:
            if lock_acquired:
                state_lock.release()

    def _release_planner_reservation(self, reservation):
        with self._planner_reservation_lock:
            if self._planner_reservation is reservation:
                self._planner_reservation = None

    def preempt_for_chat(self):
        """Mark a queued planner request stale while the chat gate is held."""
        with self._planner_reservation_lock:
            reservation = self._planner_reservation
        if reservation is None:
            # Direct/manual planner invocations do not need a reservation, but
            # they still hold the provider gate and must be cancellable when a
            # user starts chatting.
            return self._cancel_planner_request()
        reservation["cancelled"].set()
        if not reservation["started"].is_set():
            self._release_planner_reservation(reservation)
        self._cancel_planner_request()
        return True

    async def _invoke_planner(self, context: dict[str, Any], reservation=None) -> ProactivePlan:
        timeout = self._number(
            self.connection.config.get("companion", {}),
            "planner_timeout_ms",
            "planner_timeout_seconds",
            15.0,
            1000.0,
        )
        def invoke():
            provider_lock = self._provider_lock()
            state_lock = getattr(self.connection, "companion_mutex", None)
            state_acquired = False
            provider_acquired = False

            # Acquire state then provider in one worker-side hand-off. Chat
            # uses the same order, so a reservation cannot pass its final
            # preflight while a user turn is starting.
            try:
                if state_lock is not None and hasattr(state_lock, "acquire"):
                    if not state_lock.acquire(False):
                        raise _PlannerPreempted()
                    state_acquired = True
                if reservation is not None:
                    with self._planner_reservation_lock:
                        valid = self._planner_reservation is reservation
                    if (
                        not valid
                        or reservation["cancelled"].is_set()
                        or self._connection_closed()
                        or self._chat_busy()
                    ):
                        raise _PlannerPreempted()
                elif self._connection_closed() or self._chat_busy():
                    raise _PlannerPreempted()

                # Never wait behind a user turn. The provider gate is held for
                # the entire blocking planner.plan call below.
                if not provider_lock.acquire(False):
                    raise _PlannerPreempted()
                provider_acquired = True

                # Revalidate after acquiring the provider gate while the state
                # gate is still held. This closes the check-to-call race.
                if reservation is not None:
                    with self._planner_reservation_lock:
                        valid = self._planner_reservation is reservation
                    if (
                        not valid
                        or reservation["cancelled"].is_set()
                        or self._connection_closed()
                        or self._chat_busy()
                    ):
                        raise _PlannerPreempted()
                    reservation["started"].set()
                elif self._connection_closed() or self._chat_busy():
                    raise _PlannerPreempted()
            except BaseException:
                if provider_acquired:
                    provider_lock.release()
                raise
            finally:
                if state_acquired:
                    state_lock.release()

            request_session_id = f"{getattr(self.planner, 'session_id', 'proactive')}:request:{uuid.uuid4().hex}"
            try:
                return self.planner.plan(
                    **context,
                    request_session_id=request_session_id,
                )
            finally:
                provider_lock.release()
                if reservation is not None:
                    reservation["finished"] = True

        if self._planner_future is not None and not self._planner_future.done():
            raise _PlannerBusy()

        loop = asyncio.get_running_loop()
        executor = self._planner_executor
        if executor is None:
            # Keep direct/manual invocations bounded too. The default
            # asyncio executor is process-wide and can create one worker per
            # timed-out connection.
            executor = ThreadPoolExecutor(
                max_workers=1, thread_name_prefix="companion-planner"
            )
            self._planner_executor = executor
        future = loop.run_in_executor(executor, invoke)
        self._planner_future = future
        self._planner_cancel_notified = False

        def consume_planner_result(completed):
            # A timed-out/cancelled await leaves the worker running. Consume a
            # late exception and clear the slot only for this exact request so
            # a late callback cannot clobber a newer request.
            try:
                completed.exception()
            except BaseException:
                pass
            if self._planner_future is completed:
                self._planner_future = None
                self._planner_cancel_notified = False
            if reservation is not None:
                self._release_planner_reservation(reservation)

        future.add_done_callback(consume_planner_result)
        try:
            result = await asyncio.wait_for(
                asyncio.shield(future), timeout=max(0.1, timeout)
            )
        except asyncio.TimeoutError:
            self._cancel_planner_request()
            raise
        except asyncio.CancelledError:
            self._cancel_planner_request()
            raise
        finally:
            if future.done():
                consume_planner_result(future)
        return result

    def _cancel_planner_request(self) -> bool:
        """Ask the provider to stop the current request without waiting."""
        future = self._planner_future
        if future is None or future.done() or self._planner_cancel_notified:
            return False
        self._planner_cancel_notified = True
        cancel_provider = getattr(self.planner, "cancel", None)
        if not callable(cancel_provider):
            return False
        try:
            cancel_provider()
            return True
        except Exception as error:
            self._log("主动陪伴规划取消失败", error)
            return False

    def _log_plan(self, plan: ProactivePlan, epoch: int) -> None:
        logger = getattr(self.connection, "logger", None)
        if logger is not None:
            logger.bind(tag="companion_loop").info(
                f"主动陪伴规划结果: action={plan.action} reason={plan.reason_code} "
                f"chars={len(plan.text or '')} epoch={epoch} current_epoch={self.activity_epoch}"
            )

    def _log(self, message: str, error: Exception | None = None) -> None:
        logger = getattr(self.connection, "logger", None)
        if logger is not None:
            if error is None:
                logger.bind(tag="companion_loop").info(message)
            else:
                logger.bind(tag="companion_loop").warning(
                    f"{message}: {type(error).__name__}"
                )

    def _emit_event(self, event_type: str, summary: str, details: dict[str, Any] | None = None) -> None:
        reporter = getattr(self.connection, "emit_debug_event", None)
        if not callable(reporter):
            return
        try:
            reporter("conversation", event_type, "info", summary, details=details or {})
        except Exception:
            pass
