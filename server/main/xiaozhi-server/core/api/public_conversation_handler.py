from __future__ import annotations

import json
from typing import Any

from aiohttp import WSMsgType, web

from core.public_conversation.protocol import AudioTurnInput, TextTurnInput
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

        await ws.send_json(session.ready().to_dict())
        async for message in ws:
            if message.type == WSMsgType.TEXT:
                try:
                    payload = json.loads(message.data)
                    events = await self._handle_payload(session, payload)
                    for event in events:
                        await ws.send_json(event.to_dict())
                except Exception:
                    await self._send_error(ws, "invalid_request", "请求格式无效")
            elif message.type == WSMsgType.ERROR:
                break
            elif message.type == WSMsgType.CLOSE:
                break
            else:
                await self._send_error(ws, "unsupported_frame", "当前仅支持 JSON 控制帧")
        self.service.close(conversation_id)
        return ws

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
