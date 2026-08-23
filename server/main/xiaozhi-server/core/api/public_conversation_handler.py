from __future__ import annotations

import json
import base64
from typing import Any

from aiohttp import WSMsgType, web

from core.public_conversation.protocol import AudioTurnInput, TextTurnInput
from core.public_conversation.protocol import MAX_AUDIO_BASE64_LENGTH
from core.public_conversation.service import PublicConversationService


class PublicConversationHandler:
    def __init__(self, service: PublicConversationService):
        self.service = service

    @staticmethod
    async def _send_error(ws: web.WebSocketResponse, code: str, message: str, *, close: bool = False) -> None:
        await ws.send_json({"type": "error", "details": {"code": code, "message": message, "retryable": False}})
        if close:
            await ws.close(code=1008, message=message.encode("utf-8"))

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

        await ws.send_json(session.ready().to_dict())
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
                        request_id = pending_audio_id
                        data = base64.b64encode(bytes(pending_audio)).decode("ascii")
                        pending_audio_id = None
                        pending_audio.clear()
                        events = await session.handle_audio(AudioTurnInput(request_id, 0, data, True))
                    else:
                        events = await self._handle_payload(session, payload)
                    await self._send_events(ws, events, binary_audio)
                except Exception:
                    await self._send_error(ws, "invalid_request", "请求格式无效")
            elif message.type == WSMsgType.BINARY:
                if pending_audio_id is None or len(pending_audio) + len(message.data) > MAX_AUDIO_BASE64_LENGTH:
                    await self._send_error(ws, "invalid_audio", "音频帧无效或超出大小限制")
                    pending_audio_id = None
                    pending_audio.clear()
                    continue
                pending_audio.extend(message.data)
            elif message.type == WSMsgType.ERROR:
                break
            elif message.type == WSMsgType.CLOSE:
                break
            else:
                await self._send_error(ws, "unsupported_frame", "当前仅支持 JSON 控制帧")
        self.service.close(conversation_id)
        return ws

    @staticmethod
    async def _send_events(ws: web.WebSocketResponse, events: list[Any], binary_audio: bool) -> None:
        for event in events:
            payload = event.to_dict()
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
                continue
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
