import asyncio
import copy
import inspect
import logging
import queue
import re
import threading
import time
from typing import Any, Callable, Dict, Optional

from config.manage_api_client import close_current_async_client, report_debug_event


_CATEGORIES = frozenset({"conversation", "model_tool", "audio", "device"})
_LEVELS = frozenset({"debug", "info", "warning", "error"})
_EVENT_TYPE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")
_STOP = object()
_LOGGER = logging.getLogger(__name__)


class DebugEventReporter:
    def __init__(
        self,
        device_ref: str,
        session_id: Optional[str],
        sender: Optional[Callable[[Dict[str, Any]], Any]] = None,
        queue_size: int = 256,
    ):
        if queue_size <= 0:
            raise ValueError("queue_size must be greater than zero")
        self.device_ref = device_ref
        self.session_id = session_id
        self._sender = sender or report_debug_event
        self._queue = queue.Queue(maxsize=queue_size)
        self._stopped = threading.Event()
        self._state_lock = threading.Lock()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def emit(
        self,
        category: str,
        event_type: str,
        level: str,
        summary: str,
        *,
        details: Optional[Dict[str, Any]] = None,
        sentence_id: Optional[str] = None,
        occurred_at: Optional[int] = None,
        duration_ms: Optional[int] = None,
    ) -> bool:
        if not self._is_valid(category, event_type, level):
            return False

        try:
            details_snapshot = {} if details is None else copy.deepcopy(details)
        except Exception:
            return False

        payload = {
            "deviceRef": self.device_ref,
            "sessionId": self.session_id,
            "sentenceId": sentence_id,
            "category": category,
            "eventType": event_type,
            "level": level,
            "summary": str(summary),
            "details": details_snapshot,
            "occurredAt": int(time.time() * 1000) if occurred_at is None else occurred_at,
            "durationMs": duration_ms,
        }
        with self._state_lock:
            if self._stopped.is_set():
                return False
            try:
                self._queue.put_nowait(payload)
            except queue.Full:
                return False
            return True

    def close(self) -> None:
        with self._state_lock:
            if self._stopped.is_set():
                return
            self._stopped.set()
            try:
                self._queue.put_nowait(_STOP)
            except queue.Full:
                pass

    def flush_for_test(self, timeout: float = 1.0) -> bool:
        finished = threading.Event()

        def wait_for_queue():
            self._queue.join()
            finished.set()

        threading.Thread(target=wait_for_queue, daemon=True).start()
        return finished.wait(timeout)

    @staticmethod
    def _is_valid(category: str, event_type: str, level: str) -> bool:
        return (
            isinstance(category, str)
            and category in _CATEGORIES
            and isinstance(level, str)
            and level in _LEVELS
            and isinstance(event_type, str)
            and _EVENT_TYPE.fullmatch(event_type) is not None
        )

    def _run(self) -> None:
        loop = asyncio.new_event_loop()
        try:
            while True:
                try:
                    payload = self._queue.get(timeout=0.1)
                except queue.Empty:
                    with self._state_lock:
                        if self._stopped.is_set() and self._queue.empty():
                            break
                    continue
                try:
                    if payload is _STOP:
                        continue
                    result = self._sender(payload)
                    if inspect.isawaitable(result):
                        loop.run_until_complete(result)
                except Exception as error:
                    _LOGGER.debug(
                        "Debug event sender failed: %s",
                        type(error).__name__,
                        exc_info=False,
                    )
                finally:
                    self._queue.task_done()
        finally:
            try:
                loop.run_until_complete(close_current_async_client())
            except Exception as error:
                _LOGGER.debug(
                    "Debug event client cleanup failed: %s",
                    type(error).__name__,
                    exc_info=False,
                )
            finally:
                loop.close()
