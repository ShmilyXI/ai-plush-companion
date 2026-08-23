import time
import time as wall_clock

import pytest
from aiohttp import WSMsgType, web
from aiohttp.test_utils import TestClient, TestServer

from core.api.public_conversation_handler import PublicConversationHandler
from core.public_conversation.protocol import RuntimeTokenClaims
from core.public_conversation.session import PublicConversationSession


class FakeLlm:
    def response(self, _session_id, _dialogue):
        return iter(["你好。"])


class SlowLlm:
    def response(self, _session_id, _dialogue):
        wall_clock.sleep(0.2)
        return iter(["慢回复"])


class FakeTts:
    def to_playground_wav(self, _text):
        return b"audio"


class FakeAsr:
    async def to_playground_text(self, raw):
        assert raw == b"pcm"
        return "音频输入"


def session():
    now = int(time.time())
    claims = RuntimeTokenClaims("conversation-a", "user-a", "agent-a", 1,
                                ("conversation:text", "conversation:audio"),
                                ("text", "audio"), ("text", "audio"), now - 1, now + 900)
    return PublicConversationSession(
        claims,
        {"conversation_id": "conversation-a", "agent_id": "agent-a", "agent_version": 1, "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(),
        tts_factory=lambda _model: FakeTts(),
    )


class FakeService:
    def __init__(self):
        self.session = session()
        self.session._asr_factory = lambda _model: FakeAsr()

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


@pytest.mark.asyncio
async def test_binary_audio_transport_sends_metadata_then_binary_tts_frame():
    handler = PublicConversationHandler(FakeService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect(
            "/api/v1/conversations/conversation-a/stream",
            headers={"Authorization": "Bearer runtime-token", "X-Audio-Transport": "binary"},
        )
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({"type": "turn.text", "request_id": "request-binary", "text": "你好"})
        assert (await ws.receive_json())["type"] == "turn.started"
        assert (await ws.receive_json())["type"] == "llm.delta"
        metadata = await ws.receive_json()
        assert metadata["type"] == "tts.audio"
        assert metadata["details"]["transport"] == "binary"
        assert "data" not in metadata["details"]
        audio = await ws.receive()
        assert audio.type == WSMsgType.BINARY
        assert audio.data == b"audio"
        assert (await ws.receive_json())["type"] == "turn.completed"
        await ws.close()
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_binary_audio_control_frames_are_buffered_until_end():
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
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({"type": "turn.audio.start", "request_id": "request-audio"})
        await ws.send_bytes(b"pcm")
        await ws.send_json({"type": "turn.audio.end"})
        events = [await ws.receive_json() for _ in range(5)]
        assert [event["type"] for event in events] == [
            "turn.started", "asr.final", "llm.delta", "tts.audio", "turn.completed"
        ]
        assert events[1]["details"]["text"] == "音频输入"
        await ws.close()
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_cancel_is_processed_while_a_turn_is_running():
    slow_service = FakeService()
    slow_service.session._llm_factory = lambda _model: SlowLlm()
    handler = PublicConversationHandler(slow_service)
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
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({"type": "turn.text", "request_id": "request-cancel", "text": "你好"})
        started = await ws.receive_json()
        assert started["type"] == "turn.started"
        await ws.send_json({"type": "turn.cancel", "turn_id": started["turn_id"]})
        cancelled = await ws.receive_json()
        assert cancelled["type"] == "turn.cancelled"
        assert cancelled["turn_id"] == started["turn_id"]
        await ws.close()
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connection_rejects_a_third_concurrent_turn():
    slow_service = FakeService()
    slow_service.session._llm_factory = lambda _model: SlowLlm()
    handler = PublicConversationHandler(slow_service)
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
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({"type": "turn.text", "request_id": "request-one", "text": "一"})
        await ws.send_json({"type": "turn.text", "request_id": "request-two", "text": "二"})
        started = [await ws.receive_json(), await ws.receive_json()]
        assert [item["type"] for item in started] == ["turn.started", "turn.started"]
        await ws.send_json({"type": "turn.text", "request_id": "request-three", "text": "三"})
        limited = await ws.receive_json()
        assert limited["type"] == "error"
        assert limited["details"]["code"] == "concurrency_limit"
        await ws.close()
    finally:
        await client.close()
