import asyncio
import inspect
import queue
import re
import threading
import time
from typing import Any, Callable, Dict, Optional

from config.manage_api_client import report_debug_event


_CATEGORIES = frozenset({"conversation", "model_tool", "audio", "device"})
_LEVELS = frozenset({"debug", "info", "warning", "error"})
_EVENT_TYPE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")
_STOP = object()


def _default_sender(payload: Dict[str, Any]) -> Optional[Dict]:
    return asyncio.run(report_debug_event(payload))


class DebugEventReporter:
    def __init__(
        self,
        device_ref: str,
        session_id: Optional[str],
        sender: Optional[Callable[[Dict[str, Any]], Any]] = None,
        queue_size: int = 256,
    ):
        self.device_ref = device_ref
        self.session_id = session_id
        self._sender = sender or _default_sender
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

        payload = {
            "deviceRef": self.device_ref,
            "sessionId": self.session_id,
            "sentenceId": sentence_id,
            "category": category,
            "eventType": event_type,
            "level": level,
            "summary": str(summary),
            "details": {} if details is None else details,
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
        while not self._stopped.is_set() or not self._queue.empty():
            try:
                payload = self._queue.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                if payload is _STOP:
                    continue
                result = self._sender(payload)
                if inspect.isawaitable(result):
                    asyncio.run(result)
            except Exception:
                pass
            finally:
                self._queue.task_done()
