import pytest
from aiohttp import WSMsgType, web
from aiohttp.test_utils import TestClient, TestServer

from core.api.public_conversation_handler import PublicConversationHandler
from core.public_conversation.protocol import ConversationEvent, RuntimeTokenClaims


def _session():
    class Session:
        runtime_models = {}
        claims = RuntimeTokenClaims("conversation-a", "7", "agent-a", 4,
                                    ("conversation:audio",), ("audio",), ("text",), 0, 2**31)
        def _next_error_sequence(self): return 1
        def _event(self, event_type, sequence, turn_id, details):
            return ConversationEvent(event_type, "conversation-a", turn_id, sequence, 1710000000000, details)
        def ready(self): return self._event("session.ready", 1, None, {})
        def stream_ready(self): return self._event("stream.ready", 2, None, {"sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
        def begin_audio(self, item): return "turn-a", [self._event("turn.started", 3, "turn-a", {"request_id": item.request_id, "input_mode": "audio"})]
        async def finish_audio(self, turn_id, item, events, emit=None):
            event = self._event("asr.final", 4, turn_id, {"text": "测试"})
            if emit: await emit(event)
            return events + [event]
        async def cancel(self, turn_id): return [self._event("turn.cancelled", 5, turn_id, {})]
        def failure(self, *args, **kwargs): return self._event("error", 6, None, {})

    return Session()


class FakeService:
    async def open(self, _conversation_id, _token): return _session()
    def close(self, _conversation_id): pass


@pytest.mark.asyncio
async def test_stream_start_accepts_binary_frames_and_audio_end():
    handler = PublicConversationHandler(FakeService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect("/api/v1/conversations/conversation-a/stream", headers={"Authorization": "Bearer runtime"})
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({"type": "stream.start", "request_id": "r1", "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1}})
        assert (await ws.receive_json())["type"] == "stream.ready"
        await ws.send_bytes(b"pcm")
        await ws.send_json({"type": "stream.audio.end", "duration_ms": 100})
        assert (await ws.receive_json())["type"] == "turn.started"
        assert (await ws.receive_json())["type"] == "asr.final"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_web_protocol_alias_emits_speech_lifecycle_and_audio_turn():
    handler = PublicConversationHandler(FakeService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect("/api/v1/conversations/conversation-a/stream",
                                    headers={"Authorization": "Bearer runtime"})
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({"type": "web.session.start", "protocol_version": 1,
                            "request_id": "stream-1",
                            "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1}})
        assert (await ws.receive_json())["type"] == "stream.ready"
        await ws.send_bytes(b"pcm")
        assert (await ws.receive_json())["type"] == "speech.started"
        await ws.send_json({"type": "input.audio.commit", "request_id": "segment-1",
                            "event_id": "event-1"})
        assert (await ws.receive_json())["type"] == "speech.stopped"
        assert (await ws.receive_json())["type"] == "turn.started"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_stream_stop_closes_socket():
    handler = PublicConversationHandler(FakeService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect("/api/v1/conversations/conversation-a/stream", headers={"Authorization": "Bearer runtime"})
        await ws.receive_json()
        await ws.send_json({"type": "stream.start", "request_id": "r1", "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1}})
        await ws.receive_json()
        await ws.send_json({"type": "stream.stop"})
        assert (await ws.receive()).type in {WSMsgType.CLOSE, WSMsgType.CLOSED}
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_app_session_start_and_audio_commit_aliases_keep_stream_open():
    handler = PublicConversationHandler(FakeService())
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect(
            "/api/v1/conversations/conversation-a/stream",
            headers={"Authorization": "Bearer runtime"},
        )
        assert (await ws.receive_json())["type"] == "session.ready"
        await ws.send_json({
            "type": "web.session.start",
            "request_id": "call-1",
            "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1},
        })
        assert (await ws.receive_json())["type"] == "stream.ready"
        await ws.send_bytes(b"pcm")
        await ws.send_json({"type": "input.audio.commit", "request_id": "segment-1", "duration_ms": 100})
        events = []
        while True:
            event = await ws.receive_json()
            events.append(event)
            if event["type"] == "turn.started":
                break
        assert "speech.started" in [event["type"] for event in events]
        assert "speech.stopped" in [event["type"] for event in events]
        assert (await ws.receive_json())["type"] == "asr.final"
        await ws.send_json({"type": "stream.stop"})
        assert (await ws.receive_json())["type"] == "session.stopped"
        assert (await ws.receive()).type in {WSMsgType.CLOSE, WSMsgType.CLOSED}
    finally:
        await client.close()
