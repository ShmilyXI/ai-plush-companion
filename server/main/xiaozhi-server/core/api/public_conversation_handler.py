from __future__ import annotations

import asyncio
import base64
import json
import time
from typing import Any

from aiohttp import WSMsgType, web

from core.public_conversation.protocol import AudioTurnInput, StreamControlFrame, StreamStartInput, TextTurnInput
from core.public_conversation.protocol import MAX_AUDIO_BASE64_LENGTH, MAX_AUDIO_DURATION_MS
from core.public_conversation.service import PublicConversationService
from core.public_conversation.streaming_session import PublicStreamingSession


MAX_CONCURRENT_TURNS = 2
TURN_TIMEOUT_SECONDS = 60
SEND_TIMEOUT_SECONDS = 10
CONNECTION_TIMEOUT_SECONDS = 15 * 60


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
                          lock: asyncio.Lock | None = None) -> None:
        async def send() -> None:
            await ws.send_json({"type": "error", "details": {"code": code, "message": message, "retryable": False}})
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

        async def send_events(events: list[Any]) -> None:
            await self._send_events(ws, events, binary_audio, send_lock)

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

        def capacity_available() -> bool:
            return len(turn_tasks) < MAX_CONCURRENT_TURNS

        await send_events([session.ready()])
        loop = asyncio.get_running_loop()
        token_remaining = max(0.0, session.claims.expires_at - time.time())
        connection_deadline = loop.time() + min(self.connection_timeout_seconds, token_remaining)
        try:
            while True:
                remaining = connection_deadline - loop.time()
                if remaining <= 0:
                    await send_events([session.expired("connection_timeout")])
                    await ws.close(code=1000, message="连接时长已达到上限".encode("utf-8"))
                    break
                try:
                    message = await asyncio.wait_for(ws.receive(), timeout=remaining)
                except asyncio.TimeoutError:
                    await send_events([session.expired("connection_timeout")])
                    await ws.close(code=1000, message="连接时长已达到上限".encode("utf-8"))
                    break
                if message.type == WSMsgType.TEXT:
                    try:
                        payload = json.loads(message.data)
                        kind = payload.get("type") if isinstance(payload, dict) else None
                        if kind == "stream.start":
                            if stream_mode:
                                raise ValueError("stream already started")
                            start = StreamStartInput.from_payload(payload)
                            stream = PublicStreamingSession(session, lambda event: send_events([event]))
                            ready = stream.start(start.request_id, start.sample_rate, start.channels, start.format)
                            stream_mode = True
                            await send_events([ready])
                            continue
                        if stream_mode and kind in {"stream.audio.end", "stream.stop", "turn.cancel"}:
                            control = StreamControlFrame.from_payload(payload)
                            if control.type == "stream.stop":
                                stream.stop()
                                await ws.close(code=1000, message="stream stopped".encode())
                                break
                            if control.type == "turn.cancel":
                                await send_events(await session.cancel(control.turn_id or ""))
                                continue
                            if stream is None:
                                raise ValueError("stream is not active")
                            item = stream.end_audio(payload.get("duration_ms"))
                            if not capacity_available():
                                await self._send_error(ws, "concurrency_limit", "当前连接的并发轮次已满", lock=send_lock)
                                continue
                            turn_id, events = session.begin_audio(item)
                            await send_events(events)
                            if turn_id:
                                turn_tasks[turn_id] = asyncio.create_task(finish_audio(turn_id, item, events))
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
                            item = AudioTurnInput(request_id, 0, data, True, duration_ms)
                            turn_id, events = session.begin_audio(item)
                            await send_events(events)
                            if turn_id:
                                turn_tasks[turn_id] = asyncio.create_task(finish_audio(turn_id, item, events))
                            continue
                        if kind == "turn.text":
                            if not capacity_available():
                                await self._send_error(ws, "concurrency_limit", "当前连接的并发轮次已满", lock=send_lock)
                                continue
                            item = TextTurnInput(payload.get("request_id", ""), payload.get("text", ""))
                            turn_id, events = session.begin_text(item)
                            await send_events(events)
                            if turn_id:
                                turn_tasks[turn_id] = asyncio.create_task(finish_text(turn_id, item.text, events))
                            continue
                        if kind == "turn.cancel":
                            turn_id = payload.get("turn_id")
                            if not isinstance(turn_id, str) or not turn_id:
                                raise ValueError("turn_id is required")
                            await send_events(await session.cancel(turn_id))
                            continue
                        if kind == "conversation.history":
                            limit = payload.get("limit", 20)
                            if isinstance(limit, bool) or not isinstance(limit, int):
                                raise ValueError("limit is invalid")
                            await send_events([await session.history_async(limit)])
                            continue
                        raise ValueError("unsupported message type")
                    except Exception:
                        await self._send_error(ws, "invalid_request", "请求格式无效", lock=send_lock)
                elif message.type == WSMsgType.BINARY:
                    if stream_mode and stream is not None:
                        stream.push_audio(bytes(message.data))
                        continue
                    if pending_audio_id is None or len(pending_audio) + len(message.data) > MAX_AUDIO_BASE64_LENGTH:
                        await self._send_error(ws, "invalid_audio", "音频帧无效或超出大小限制", lock=send_lock)
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
            tasks = list(turn_tasks.values())
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            self.service.close(conversation_id)
        return ws

    async def _send_events(self, ws: web.WebSocketResponse, events: list[Any], binary_audio: bool,
                           lock: asyncio.Lock) -> None:
        for event in events:
            payload = event.to_dict()
            async with lock:
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
            return await session.handle_text(TextTurnInput(payload.get("request_id", ""), payload.get("text", "")))
        if kind == "turn.audio":
            return await session.handle_audio(AudioTurnInput(
                payload.get("request_id", ""), payload.get("sequence", 0), payload.get("data", ""),
                payload.get("final", False), payload.get("duration_ms")
            ))
        if kind == "turn.cancel":
            turn_id = payload.get("turn_id")
            if not isinstance(turn_id, str) or not turn_id:
                raise ValueError("turn_id is required")
            return await session.cancel(turn_id)
        raise ValueError("unsupported message type")
