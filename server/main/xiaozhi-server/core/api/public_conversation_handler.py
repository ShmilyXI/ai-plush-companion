from __future__ import annotations

import asyncio
import base64
import json
import time
from typing import Any

from aiohttp import WSMsgType, web

from core.public_conversation.protocol import AudioTurnInput, ConversationEvent, StreamControlFrame, StreamStartInput, TextTurnInput
from core.public_conversation.protocol import (
    MAX_AUDIO_BASE64_LENGTH,
    MAX_AUDIO_DURATION_MS,
)
from core.public_conversation.service import PublicConversationService
from core.public_conversation.streaming_session import PublicStreamingSession


MAX_CONCURRENT_TURNS = 2
TURN_TIMEOUT_SECONDS = 60
SEND_TIMEOUT_SECONDS = 10
CONNECTION_TIMEOUT_SECONDS = 15 * 60
HEARTBEAT_INTERVAL_SECONDS = 30
EXPIRY_NOTICE_SECONDS = 60


class PublicConversationBackpressureError(RuntimeError):
    pass


class PublicConversationHandler:
    def __init__(self, service: PublicConversationService):
        self.service = service
        self.turn_timeout_seconds = TURN_TIMEOUT_SECONDS
        self.send_timeout_seconds = SEND_TIMEOUT_SECONDS
        self.connection_timeout_seconds = CONNECTION_TIMEOUT_SECONDS

    @staticmethod
    async def _send_error(ws: web.WebSocketResponse, code: str, message: str, *, close: bool = False,
                          lock: asyncio.Lock | None = None, request_id: str | None = None,
                          segment_id: str | None = None, event_id: str | None = None) -> None:
        async def send() -> None:
            details = {"code": code, "message": message, "retryable": False}
            if request_id:
                details["request_id"] = request_id
            if segment_id:
                details["segment_id"] = segment_id
            if event_id:
                details["event_id"] = event_id
            await ws.send_json({"type": "error", "details": details})
            if close:
                await ws.close(code=1008, message=message.encode("utf-8"))

        if lock is None:
            await send()
        else:
            async with lock:
                await send()

    async def handle_stream(self, request: web.Request) -> web.WebSocketResponse:
        requested_protocols = [
            item.strip()
            for item in request.headers.get("Sec-WebSocket-Protocol", "").split(",")
            if item.strip()
        ]
        browser_protocol = next(
            (item for item in requested_protocols if item.startswith("bearer.")),
            None,
        )
        ws = web.WebSocketResponse(
            max_msg_size=2 * 1024 * 1024,
            protocols=[browser_protocol] if browser_protocol else (),
        )
        await ws.prepare(request)
        conversation_id = request.match_info["conversation_id"]
        authorization = request.headers.get("Authorization", "")
        token = authorization[7:] if authorization.startswith("Bearer ") else ""
        if not token and browser_protocol:
            token = browser_protocol.removeprefix("bearer.")
        try:
            session = await self.service.open(conversation_id, token)
        except Exception:
            await self._send_error(ws, "unauthorized", "会话令牌无效", close=True)
            return ws

        binary_audio = request.headers.get("X-Audio-Transport", "json").strip().lower() == "binary"
        pending_audio_id: str | None = None
        pending_audio_duration_ms: int | None = None
        pending_audio = bytearray()
        send_lock = asyncio.Lock()
        turn_tasks: dict[str, asyncio.Task[None]] = {}
        stream: PublicStreamingSession | None = None
        stream_mode = False
        web_protocol_mode = False
        wire_state = {"sequence": 0}

        def session_event(event_type: str, sequence: int, turn_id: str | None, details: dict[str, Any], **kwargs):
            try:
                return session._event(event_type, sequence, turn_id, details, **kwargs)
            except TypeError:
                # Keep compatibility with small test doubles and legacy sessions.
                return session._event(event_type, sequence, turn_id, details)

        async def send_events(events: list[Any]) -> None:
            if stream_mode:
                converted = []
                for event in events:
                    if event.event_type == "tts.audio":
                        details = dict(event.details)
                        details["chunk_index"] = 0
                        details["final"] = False
                        converted.append(ConversationEvent("tts.audio.chunk", event.conversation_id,
                                                            event.turn_id, event.sequence, event.occurred_at, details,
                                                            event.request_id, event.segment_id))
                        converted.append(session_event("tts.audio.done", session._next_error_sequence(),
                                                        event.turn_id, {"chunk_count": 1},
                                                        request_id=event.request_id,
                                                        segment_id=event.segment_id))
                    elif event.event_type == "error" and event.turn_id and (event.details or {}).get("code") in {
                        "turn_failed", "turn_timeout"
                    }:
                        converted.append(ConversationEvent("turn.failed", event.conversation_id,
                                                            event.turn_id, event.sequence, event.occurred_at,
                                                            dict(event.details), event.request_id, event.segment_id))
                    else:
                        converted.append(event)
                events = converted
            await self._send_events(ws, events, binary_audio, send_lock, wire_state)

        async def finish_text(turn_id: str, text: str, events: list[Any]) -> None:
            try:
                async def emit(event):
                    await send_events([event])

                result = await asyncio.wait_for(
                    session.finish_text(turn_id, text, events, emit), timeout=self.turn_timeout_seconds
                )
            except asyncio.TimeoutError:
                await send_events([session.failure(turn_id, "turn_timeout", "本轮处理超时")])
            except PublicConversationBackpressureError:
                await ws.close(code=1013, message="发送缓冲区超时".encode("utf-8"))
            finally:
                turn_tasks.pop(turn_id, None)

        async def finish_audio(turn_id: str, item: AudioTurnInput, events: list[Any]) -> None:
            try:
                async def emit(event):
                    await send_events([event])

                result = await asyncio.wait_for(
                    session.finish_audio(turn_id, item, events, emit), timeout=self.turn_timeout_seconds
                )
            except asyncio.TimeoutError:
                await send_events([session.failure(turn_id, "turn_timeout", "本轮处理超时")])
            except PublicConversationBackpressureError:
                await ws.close(code=1013, message="发送缓冲区超时".encode("utf-8"))
            finally:
                turn_tasks.pop(turn_id, None)

        async def start_stream_text(request_id: str | None, text: str, *, segment_id: str | None = None,
                                    event_id: str | None = None) -> bool:
            if not request_id or not text:
                return False
            if not capacity_available():
                await self._send_error(ws, "concurrency_limit", "当前连接的并发轮次已满", lock=send_lock)
                return True
            begin_transcript = getattr(session, "begin_audio_transcript", None)
            if callable(begin_transcript):
                turn_id, events = begin_transcript(request_id, text, segment_id, event_id)
            else:
                turn_id, events = session.begin_text(TextTurnInput(request_id, text))
            await send_events(events)
            if not turn_id:
                return True
            if web_protocol_mode:
                await send_events([session_event("speech.stopped", session._next_error_sequence(), None, {
                    "request_id": request_id,
                }, request_id=request_id, segment_id=segment_id, event_id=event_id)])
            await send_events([session_event("asr.final", session._next_error_sequence(), turn_id,
                                              {"text": text}, request_id=request_id, segment_id=segment_id)])
            if turn_id:
                task = asyncio.create_task(finish_text(turn_id, text, events))
                turn_tasks[turn_id] = task
                attach = getattr(session, "attach_task", None)
                if callable(attach):
                    attach(turn_id, task)
            return bool(turn_id)

        def capacity_available() -> bool:
            return len(turn_tasks) < MAX_CONCURRENT_TURNS

        await send_events([session.ready()])
        loop = asyncio.get_running_loop()
        token_remaining = max(0.0, session.claims.expires_at - time.time())
        connection_deadline = loop.time() + min(self.connection_timeout_seconds, token_remaining)
        heartbeat_deadline = loop.time() + HEARTBEAT_INTERVAL_SECONDS
        expiry_notice_at = connection_deadline - EXPIRY_NOTICE_SECONDS
        expiring_sent = expiry_notice_at <= loop.time()
        try:
            while True:
                remaining = connection_deadline - loop.time()
                if remaining <= 0:
                    await send_events([session.expired("connection_timeout")])
                    await ws.close(code=1000, message="连接时长已达到上限".encode("utf-8"))
                    break
                now_loop = loop.time()
                if not expiring_sent and now_loop >= expiry_notice_at:
                    await send_events([session_event("session.expiring", session._next_error_sequence(), None, {
                        "expires_at": session.claims.expires_at,
                        "remaining_ms": max(0, int(remaining * 1000)),
                    })])
                    expiring_sent = True
                wait_timeout = min(remaining, max(0.1, heartbeat_deadline - now_loop))
                if not expiring_sent and expiry_notice_at > now_loop:
                    wait_timeout = min(wait_timeout, expiry_notice_at - now_loop)
                try:
                    message = await asyncio.wait_for(ws.receive(), timeout=wait_timeout)
                except asyncio.TimeoutError:
                    now_loop = loop.time()
                    if now_loop >= connection_deadline:
                        await send_events([session.expired("connection_timeout")])
                        await ws.close(code=1000, message="连接时长已达到上限".encode("utf-8"))
                        break
                    if not expiring_sent and now_loop >= expiry_notice_at:
                        await send_events([session_event("session.expiring", session._next_error_sequence(), None, {
                            "expires_at": session.claims.expires_at,
                            "remaining_ms": max(0, int((connection_deadline - now_loop) * 1000)),
                        })])
                        expiring_sent = True
                    if now_loop >= heartbeat_deadline:
                        await send_events([session_event("session.heartbeat", session._next_error_sequence(), None, {
                            "expires_at": session.claims.expires_at,
                        })])
                        heartbeat_deadline = now_loop + HEARTBEAT_INTERVAL_SECONDS
                    continue
                if message.type == WSMsgType.TEXT:
                    try:
                        payload = json.loads(message.data)
                        kind = payload.get("type") if isinstance(payload, dict) else None
                        web_session_start = kind == "web.session.start"
                        if web_session_start:
                            # Text-only App clients send a harmless preamble;
                            # continuous audio starts only when an audio format
                            # is explicitly negotiated.
                            if payload.get("audio") is None:
                                continue
                            audio = payload.get("audio") or {}
                            payload = {
                                "type": "stream.start",
                                "request_id": payload.get("request_id") or f"session-{conversation_id}",
                                "audio": {
                                    "format": audio.get("format", "pcm_s16le"),
                                    "sample_rate": audio.get("sample_rate", 16000),
                                    "channels": audio.get("channels", 1),
                                },
                            }
                            kind = "stream.start"
                        if kind in {"stream.start", "web.session.start"}:
                            if stream_mode:
                                raise ValueError("stream already started")
                            start = StreamStartInput.from_payload(payload)
                            web_protocol_mode = web_session_start
                            stream = PublicStreamingSession(session, lambda event: send_events([event]), start_stream_text)
                            ready = await stream.start(start.request_id, start.sample_rate, start.channels, start.format,
                                                       start.event_id)
                            stream_mode = True
                            await send_events([ready])
                            continue
                        if stream_mode and kind in {"stream.audio.end", "input.audio.commit", "stream.stop", "web.session.stop", "turn.cancel", "response.cancel"}:
                            control = StreamControlFrame.from_payload(payload)
                            if control.type == "stream.stop":
                                if web_protocol_mode:
                                    await send_events([session_event("session.stopped", session._next_error_sequence(), None, {
                                        "reason": "client_stopped",
                                    }, request_id=control.request_id)])
                                await stream.aclose()
                                await ws.close(code=1000, message="stream stopped".encode())
                                break
                            if control.type == "turn.cancel":
                                if kind == "response.cancel":
                                    await send_events([session_event("turn.interrupted", session._next_error_sequence(), control.turn_id, {
                                        "played_ms": max(0, int(payload.get("played_ms", 0) or 0)),
                                    }, event_id=control.event_id)])
                                await send_events(await session.cancel(control.turn_id or ""))
                                continue
                            if stream is None:
                                raise ValueError("stream is not active")
                            item = await stream.end_audio(control.request_id, payload.get("duration_ms"), control.event_id)
                            if item is None:
                                continue
                            if web_protocol_mode:
                                await send_events([session_event("speech.stopped", session._next_error_sequence(), None, {
                                    "request_id": item.request_id,
                                }, request_id=item.request_id, segment_id=item.segment_id)])
                            if not capacity_available():
                                await self._send_error(ws, "concurrency_limit", "当前连接的并发轮次已满", lock=send_lock)
                                continue
                            final_text = stream.last_final_text
                            if final_text:
                                begin_transcript = getattr(session, "begin_audio_transcript", None)
                                if callable(begin_transcript):
                                    turn_id, events = begin_transcript(
                                        item.request_id, final_text, item.segment_id, item.event_id
                                    )
                                else:
                                    turn_id, events = session.begin_text(TextTurnInput(
                                        item.request_id, final_text, item.segment_id
                                    ))
                            else:
                                turn_id, events = session.begin_audio(item)
                            await send_events(events)
                            if turn_id:
                                if final_text:
                                    await send_events([session_event("asr.final", session._next_error_sequence(), turn_id,
                                                                      {"text": final_text}, request_id=item.request_id,
                                                                      segment_id=item.segment_id)])
                                    task = asyncio.create_task(finish_text(turn_id, final_text, events))
                                else:
                                    task = asyncio.create_task(finish_audio(turn_id, item, events))
                                turn_tasks[turn_id] = task
                                attach = getattr(session, "attach_task", None)
                                if callable(attach):
                                    attach(turn_id, task)
                            continue
                        if kind == "turn.audio.start":
                            if pending_audio_id is not None:
                                raise ValueError("audio turn is already open")
                            request_id = payload.get("request_id")
                            if not isinstance(request_id, str) or not request_id.strip():
                                raise ValueError("request_id is required")
                            duration_ms = payload.get("duration_ms")
                            if duration_ms is not None and (
                                isinstance(duration_ms, bool)
                                or not isinstance(duration_ms, int)
                                or duration_ms <= 0
                                or duration_ms > MAX_AUDIO_DURATION_MS
                            ):
                                await self._send_error(ws, "invalid_audio", "音频时长无效或超出单轮限制", lock=send_lock)
                                continue
                            pending_audio_id = request_id
                            pending_audio_duration_ms = duration_ms
                            pending_audio.clear()
                            continue
                        if kind == "turn.audio.end":
                            if pending_audio_id is None:
                                raise ValueError("audio turn is not open")
                            if not capacity_available():
                                await self._send_error(ws, "concurrency_limit", "当前连接的并发轮次已满", lock=send_lock)
                                pending_audio_id = None
                                pending_audio.clear()
                                continue
                            request_id = pending_audio_id
                            data = base64.b64encode(bytes(pending_audio)).decode("ascii")
                            pending_audio_id = None
                            duration_ms = pending_audio_duration_ms
                            pending_audio_duration_ms = None
                            pending_audio.clear()
                            item = AudioTurnInput(request_id, 0, data, True, duration_ms,
                                                  payload.get("segment_id"), payload.get("event_id"))
                            turn_id, events = session.begin_audio(item)
                            await send_events(events)
                            if turn_id:
                                task = asyncio.create_task(finish_audio(turn_id, item, events))
                                turn_tasks[turn_id] = task
                                attach = getattr(session, "attach_task", None)
                                if callable(attach):
                                    attach(turn_id, task)
                            continue
                        if kind == "turn.text":
                            if not capacity_available():
                                await self._send_error(ws, "concurrency_limit", "当前连接的并发轮次已满", lock=send_lock)
                                continue
                            item = TextTurnInput(payload.get("request_id", ""), payload.get("text", ""),
                                                 payload.get("segment_id"), payload.get("event_id"))
                            turn_id, events = session.begin_text(item)
                            await send_events(events)
                            if turn_id:
                                task = asyncio.create_task(finish_text(turn_id, item.text, events))
                                turn_tasks[turn_id] = task
                                attach = getattr(session, "attach_task", None)
                                if callable(attach):
                                    attach(turn_id, task)
                            continue
                        if kind in {"turn.cancel", "response.cancel"}:
                            turn_id = payload.get("turn_id")
                            if not isinstance(turn_id, str) or not turn_id:
                                raise ValueError("turn_id is required")
                            if kind == "response.cancel":
                                await send_events([session_event("turn.interrupted", session._next_error_sequence(), turn_id, {
                                    "played_ms": max(0, int(payload.get("played_ms", 0) or 0)),
                                }, event_id=payload.get("event_id") if isinstance(payload.get("event_id"), str) else None)])
                            await send_events(await session.cancel(turn_id))
                            continue
                        if kind == "conversation.history":
                            limit = payload.get("limit", 20)
                            if isinstance(limit, bool) or not isinstance(limit, int):
                                raise ValueError("limit is invalid")
                            await send_events([await session.history_async(limit)])
                            continue
                        if kind in {"heartbeat", "session.ping", "web.session.ping", "session.heartbeat", "web.session.heartbeat"}:
                            last_sequence = payload.get("last_sequence")
                            if last_sequence is not None and (
                                isinstance(last_sequence, bool)
                                or not isinstance(last_sequence, int)
                                or last_sequence < 0
                            ):
                                raise ValueError("last_sequence is invalid")
                            heartbeat = getattr(session, "heartbeat", None)
                            if callable(heartbeat):
                                event = heartbeat(last_sequence)
                            else:
                                event = session_event("session.pong", session._next_error_sequence(), None, {
                                    "nonce": payload.get("nonce"),
                                    "last_sequence": last_sequence,
                                })
                            await send_events([event])
                            continue
                        raise ValueError("unsupported message type")
                    except Exception:
                        request_id = payload.get("request_id") if isinstance(payload, dict) else None
                        segment_id = payload.get("segment_id") if isinstance(payload, dict) else None
                        event_id = payload.get("event_id") if isinstance(payload, dict) else None
                        await self._send_error(ws, "invalid_request", "请求格式无效", lock=send_lock,
                                               request_id=request_id, segment_id=segment_id, event_id=event_id)
                elif message.type == WSMsgType.BINARY:
                    if stream_mode and stream is not None:
                        try:
                            if web_protocol_mode and not stream.audio:
                                await send_events([session_event("speech.started", session._next_error_sequence(), None, {
                                    "request_id": stream.request_id,
                                }, request_id=stream.request_id, segment_id=stream.segment_id)])
                            await stream.push_audio(bytes(message.data))
                        except ValueError as error:
                            await self._send_error(ws, "invalid_audio", str(error), lock=send_lock)
                        continue
                    if pending_audio_id is None or len(pending_audio) + len(message.data) > MAX_AUDIO_BASE64_LENGTH:
                        code = "stream_not_started" if not stream_mode else "invalid_audio"
                        await self._send_error(ws, code, "请先发送 stream.start" if code == "stream_not_started" else "音频帧无效或超出大小限制", lock=send_lock)
                        pending_audio_id = None
                        pending_audio_duration_ms = None
                        pending_audio.clear()
                        continue
                    pending_audio.extend(message.data)
                elif message.type in {WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSED}:
                    break
                else:
                    await self._send_error(ws, "unsupported_frame", "当前仅支持 JSON 控制帧和音频二进制帧", lock=send_lock)
        finally:
            pending_audio_id = None
            pending_audio_duration_ms = None
            pending_audio.clear()
            if stream is not None:
                await stream.aclose()
            tasks = list(turn_tasks.values())
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            close_session = getattr(session, "aclose", None)
            if callable(close_session):
                try:
                    result = close_session()
                    if hasattr(result, "__await__"):
                        await result
                except Exception:
                    # Socket cleanup must still reach the service index even if
                    # a provider's close hook is faulty.
                    pass
            self.service.close(conversation_id)
        return ws

    async def _send_events(self, ws: web.WebSocketResponse, events: list[Any], binary_audio: bool,
                           lock: asyncio.Lock, wire_state: dict[str, int] | None = None) -> None:
        state = wire_state if wire_state is not None else {"sequence": 0}
        async with lock:
            for event in events:
                payload = event.to_dict()
                original_sequence = payload.get("sequence")
                next_sequence = max(
                    int(original_sequence) if isinstance(original_sequence, int) else 0,
                    state["sequence"] + 1,
                )
                if isinstance(original_sequence, int) and original_sequence != next_sequence:
                    payload["event_sequence"] = original_sequence
                payload["sequence"] = next_sequence
                state["sequence"] = next_sequence
                payload["wire_sequence"] = next_sequence
                if binary_audio and payload.get("type") == "tts.audio":
                    details = dict(payload.get("details") or {})
                    encoded = details.pop("data", None)
                    if not isinstance(encoded, str):
                        raise ValueError("tts audio payload is invalid")
                    audio = base64.b64decode(encoded, validate=True)
                    details["transport"] = "binary"
                    details["audio_sequence"] = payload.get("sequence")
                    details["byte_length"] = len(audio)
                    payload["details"] = details
                    await self._send_frame(ws.send_json(payload), ws)
                    await self._send_frame(ws.send_bytes(audio), ws)
                elif binary_audio and payload.get("type") == "tts.audio.chunk":
                    details = dict(payload.get("details") or {})
                    encoded = details.pop("data", None)
                    if not isinstance(encoded, str):
                        raise ValueError("tts audio payload is invalid")
                    audio = base64.b64decode(encoded, validate=True)
                    details["transport"] = "binary"
                    details["audio_sequence"] = payload.get("sequence")
                    details["byte_length"] = len(audio)
                    payload["details"] = details
                    await self._send_frame(ws.send_json(payload), ws)
                    await self._send_frame(ws.send_bytes(audio), ws)
                else:
                    await self._send_frame(ws.send_json(payload), ws)

    async def _send_frame(self, awaitable, ws: web.WebSocketResponse) -> None:
        try:
            await asyncio.wait_for(awaitable, timeout=self.send_timeout_seconds)
        except asyncio.TimeoutError as error:
            await ws.close(code=1013, message="发送缓冲区超时".encode("utf-8"))
            raise PublicConversationBackpressureError("websocket send timed out") from error

    @staticmethod
    async def _handle_payload(session: Any, payload: dict[str, Any]) -> list[Any]:
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")
        kind = payload.get("type")
        if kind == "turn.text":
            return await session.handle_text(TextTurnInput(payload.get("request_id", ""), payload.get("text", ""),
                                                           payload.get("segment_id"), payload.get("event_id")))
        if kind == "turn.audio":
            return await session.handle_audio(AudioTurnInput(
                payload.get("request_id", ""), payload.get("sequence", 0), payload.get("data", ""),
                payload.get("final", False), payload.get("duration_ms"), payload.get("segment_id"),
                payload.get("event_id")
            ))
        if kind == "turn.cancel":
            turn_id = payload.get("turn_id")
            if not isinstance(turn_id, str) or not turn_id:
                raise ValueError("turn_id is required")
            return await session.cancel(turn_id)
        raise ValueError("unsupported message type")
