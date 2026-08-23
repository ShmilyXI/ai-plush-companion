from __future__ import annotations

import asyncio
import base64
import json
from typing import Any

from aiohttp import WSMsgType, web

from core.public_conversation.protocol import AudioTurnInput, TextTurnInput
from core.public_conversation.protocol import MAX_AUDIO_BASE64_LENGTH
from core.public_conversation.service import PublicConversationService


MAX_CONCURRENT_TURNS = 2


class PublicConversationHandler:
    def __init__(self, service: PublicConversationService):
        self.service = service

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
        ws = web.WebSocketResponse(max_msg_size=2 * 1024 * 1024)
        await ws.prepare(request)
        conversation_id = request.match_info["conversation_id"]
        authorization = request.headers.get("Authorization", "")
        token = authorization[7:] if authorization.startswith("Bearer ") else ""
        try:
            session = await self.service.open(conversation_id, token)
        except Exception:
            await self._send_error(ws, "unauthorized", "会话令牌无效", close=True)
            return ws

        binary_audio = request.headers.get("X-Audio-Transport", "json").strip().lower() == "binary"
        pending_audio_id: str | None = None
        pending_audio = bytearray()
        send_lock = asyncio.Lock()
        turn_tasks: dict[str, asyncio.Task[None]] = {}

        async def send_events(events: list[Any]) -> None:
            await self._send_events(ws, events, binary_audio, send_lock)

        async def finish_text(turn_id: str, text: str, events: list[Any]) -> None:
            try:
                initial_length = len(events)
                result = await session.finish_text(turn_id, text, events)
                await send_events(result[initial_length:])
            finally:
                turn_tasks.pop(turn_id, None)

        async def finish_audio(turn_id: str, item: AudioTurnInput, events: list[Any]) -> None:
            try:
                initial_length = len(events)
                result = await session.finish_audio(turn_id, item, events)
                await send_events(result[initial_length:])
            finally:
                turn_tasks.pop(turn_id, None)

        def capacity_available() -> bool:
            return len(turn_tasks) < MAX_CONCURRENT_TURNS

        await send_events([session.ready()])
        try:
            async for message in ws:
                if message.type == WSMsgType.TEXT:
                    try:
                        payload = json.loads(message.data)
                        kind = payload.get("type") if isinstance(payload, dict) else None
                        if kind == "turn.audio.start":
                            if pending_audio_id is not None:
                                raise ValueError("audio turn is already open")
                            request_id = payload.get("request_id")
                            if not isinstance(request_id, str) or not request_id.strip():
                                raise ValueError("request_id is required")
                            pending_audio_id = request_id
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
                            pending_audio.clear()
                            item = AudioTurnInput(request_id, 0, data, True)
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
                        raise ValueError("unsupported message type")
                    except Exception:
                        await self._send_error(ws, "invalid_request", "请求格式无效", lock=send_lock)
                elif message.type == WSMsgType.BINARY:
                    if pending_audio_id is None or len(pending_audio) + len(message.data) > MAX_AUDIO_BASE64_LENGTH:
                        await self._send_error(ws, "invalid_audio", "音频帧无效或超出大小限制", lock=send_lock)
                        pending_audio_id = None
                        pending_audio.clear()
                        continue
                    pending_audio.extend(message.data)
                elif message.type in {WSMsgType.ERROR, WSMsgType.CLOSE}:
                    break
                else:
                    await self._send_error(ws, "unsupported_frame", "当前仅支持 JSON 控制帧和音频二进制帧", lock=send_lock)
        finally:
            pending_audio_id = None
            pending_audio.clear()
            tasks = list(turn_tasks.values())
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            self.service.close(conversation_id)
        return ws

    @staticmethod
    async def _send_events(ws: web.WebSocketResponse, events: list[Any], binary_audio: bool,
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
                    await ws.send_json(payload)
                    await ws.send_bytes(audio)
                else:
                    await ws.send_json(payload)

    @staticmethod
    async def _handle_payload(session: Any, payload: dict[str, Any]) -> list[Any]:
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")
        kind = payload.get("type")
        if kind == "turn.text":
            return await session.handle_text(TextTurnInput(payload.get("request_id", ""), payload.get("text", "")))
        if kind == "turn.audio":
            return await session.handle_audio(AudioTurnInput(
                payload.get("request_id", ""), payload.get("sequence", 0), payload.get("data", ""), payload.get("final", False)
            ))
        if kind == "turn.cancel":
            turn_id = payload.get("turn_id")
            if not isinstance(turn_id, str) or not turn_id:
                raise ValueError("turn_id is required")
            return await session.cancel(turn_id)
        raise ValueError("unsupported message type")
