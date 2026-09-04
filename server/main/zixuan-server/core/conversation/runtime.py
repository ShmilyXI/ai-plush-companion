from __future__ import annotations

import asyncio
import inspect
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from dataclasses import replace
from typing import Any, Protocol

from .contract import ConversationEvent, ConversationHandle, ConversationInput, ConversationRequest
from .events import event


Processor = Callable[[ConversationRequest, ConversationInput, Callable[..., Awaitable[None]]], Any]


class ConversationRuntime:
    """Owns request snapshots, turn lifecycle, and provider-independent events."""

    def __init__(self, *, processor: Processor | None = None, dependencies: Mapping[str, Any] | None = None):
        self.processor = processor
        self.dependencies = dict(dependencies or {})

    async def start(self, request: ConversationRequest) -> ConversationHandle:
        if not isinstance(request, ConversationRequest):
            raise TypeError("request must be a ConversationRequest")
        if request.capability_bundle is not None:
            request.capability_bundle.validate_for_runtime()
        return _ConversationHandle(request, self.processor, self.dependencies)


class _ConversationHandle:
    _SENTINEL = object()

    def __init__(self, request: ConversationRequest, processor: Processor | None, dependencies: Mapping[str, Any]):
        self.request = request
        self._processor = processor
        self._dependencies = dict(dependencies)
        self._queue: asyncio.Queue[ConversationEvent | object] = asyncio.Queue()
        self._history: list[ConversationEvent] = []
        self._sequence = 0
        self._seen_requests: set[str] = set()
        self._tasks: dict[str, asyncio.Task[Any]] = {}
        self._turn_reasons: dict[str, str] = {}
        self._cancelled_emitted: set[str] = set()
        self._closed = False
        self._close_emitted = False
        self._events_finished = False
        self._emit("session.started", None, {
            "source": request.source,
            "profile_id": request.profile_id,
            "capability_bundle": request.capability_bundle,
        })

    @property
    def events(self) -> AsyncIterator[ConversationEvent]:
        return self._iter_events()

    @property
    def pending_events(self) -> tuple[ConversationEvent, ...]:
        return tuple(self._history)

    async def _iter_events(self) -> AsyncIterator[ConversationEvent]:
        while not self._events_finished:
            item = await self._queue.get()
            if item is self._SENTINEL:
                self._events_finished = True
                break
            yield item

    def _emit(self, kind: str, turn_id: str | None, details: Mapping[str, Any] | None = None) -> ConversationEvent:
        self._sequence += 1
        value = event(kind, self._sequence, self.request.conversation_id, turn_id, details)
        self._history.append(value)
        self._queue.put_nowait(value)
        return value

    async def send(self, input: ConversationInput) -> None:
        if self._closed:
            raise RuntimeError("conversation handle is closed")
        if input.request_id in self._seen_requests:
            self._emit("error", None, {"code": "duplicate_request", "request_id": input.request_id})
            return
        self._seen_requests.add(input.request_id)
        turn_id = uuid.uuid4().hex
        # The request object is immutable, so profile/bundle changes elsewhere
        # cannot affect this turn after it starts.
        turn_request = replace(self.request, text=input.text, audio=input.audio)
        self._emit("turn.started", turn_id, {
            "request_id": input.request_id,
            "input_mode": "audio" if input.kind != "text" else "text",
            "profile_id": turn_request.profile_id,
            "agent_version_no": getattr(turn_request.capability_bundle, "agent_version_no", None),
        })
        task = asyncio.create_task(self._run_turn(turn_id, turn_request, input))
        self._tasks[turn_id] = task
        task.add_done_callback(lambda _: self._tasks.pop(turn_id, None))

    async def _run_turn(self, turn_id: str, request: ConversationRequest, input: ConversationInput) -> None:
        try:
            if self._processor is None:
                if input.kind == "text" and input.text:
                    self._emit("llm.delta", turn_id, {"text": input.text})
            else:
                async def emit(kind: str, details: Mapping[str, Any] | None = None) -> None:
                    if not self._closed and turn_id in self._tasks:
                        self._emit(kind, turn_id, details)

                result = self._processor(request, input, emit)
                if inspect.isawaitable(result):
                    await result
            if not self._closed and turn_id not in self._turn_reasons:
                self._emit("turn.completed", turn_id, {})
        except asyncio.CancelledError:
            reason = self._turn_reasons.get(turn_id, "cancelled")
            if not self._closed and turn_id not in self._cancelled_emitted:
                self._cancelled_emitted.add(turn_id)
                self._emit("turn.cancelled", turn_id, {"reason": reason})
        except Exception as exc:
            if not self._closed:
                self._emit("error", turn_id, {"error_class": type(exc).__name__})
                self._emit("turn.failed", turn_id, {"error_class": type(exc).__name__})

    async def cancel(self, reason: str) -> None:
        if self._closed:
            return
        safe_reason = str(reason or "cancelled")[:128]
        for turn_id, task in list(self._tasks.items()):
            if task.done():
                continue
            self._turn_reasons.setdefault(turn_id, safe_reason)
            task.cancel()
            if turn_id not in self._cancelled_emitted:
                self._cancelled_emitted.add(turn_id)
                self._emit("turn.cancelled", turn_id, {"reason": safe_reason})
        await asyncio.gather(*self._tasks.values(), return_exceptions=True)

    async def close(self) -> None:
        if self._closed:
            return
        await self.cancel("session_closed")
        self._closed = True
        if not self._close_emitted:
            self._close_emitted = True
            self._emit("session.closed", None, {})
        self._queue.put_nowait(self._SENTINEL)
