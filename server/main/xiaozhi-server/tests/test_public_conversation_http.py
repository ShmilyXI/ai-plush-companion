import time

import pytest
from aiohttp import WSMsgType, web
from aiohttp.test_utils import TestClient, TestServer

from core.api.public_conversation_handler import PublicConversationHandler
from core.public_conversation.protocol import RuntimeTokenClaims
from core.public_conversation.session import PublicConversationSession


class FakeLlm:
    def response(self, _session_id, _dialogue):
        return iter(["你好。"])


class FakeTts:
    def to_playground_wav(self, _text):
        return b"audio"


def session():
    now = int(time.time())
    claims = RuntimeTokenClaims("conversation-a", "user-a", "agent-a", 1, ("conversation:text",), ("text",), ("text", "audio"), now - 1, now + 900)
    return PublicConversationSession(
        claims,
        {"conversation_id": "conversation-a", "agent_id": "agent-a", "agent_version": 1, "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(),
        tts_factory=lambda _model: FakeTts(),
    )


class FakeService:
    def __init__(self):
        self.session = session()

    async def open(self, _conversation_id, _token):
        return self.session

    def close(self, _conversation_id):
        return None


@pytest.mark.asyncio
async def test_websocket_streams_session_ready_and_text_turn_events():
    handler = PublicConversationHandler(FakeService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect(
            "/api/v1/conversations/conversation-a/stream",
            headers={"Authorization": "Bearer runtime-token"},
        )
        ready = await ws.receive_json()
        assert ready["type"] == "session.ready"
        await ws.send_json({"type": "turn.text", "request_id": "request-a", "text": "你好"})
        events = [await ws.receive_json() for _ in range(4)]
        assert [event["type"] for event in events] == [
            "turn.started", "llm.delta", "tts.audio", "turn.completed"
        ]
        await ws.close()
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_invalid_token_closes_stream_with_error():
    class RejectingService:
        async def open(self, _conversation_id, _token):
            raise ValueError("invalid")

        def close(self, _conversation_id):
            return None

    handler = PublicConversationHandler(RejectingService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect("/api/v1/conversations/conversation-a/stream")
        error = await ws.receive_json()
        assert error["details"]["code"] == "unauthorized"
        assert (await ws.receive()).type == WSMsgType.CLOSE
        assert ws.closed
    finally:
        await client.close()
