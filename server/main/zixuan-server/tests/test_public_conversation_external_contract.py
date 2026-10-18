import base64
import hashlib
import hmac
import json
import time

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from core.api.public_conversation_handler import PublicConversationHandler
from core.public_conversation.service import PublicConversationService


class FakeRuntimeClient:
    async def bundle(self, conversation_id):
        return {
            "conversation_id": conversation_id,
            "agent_id": "agent-a",
            "agent_version": 4,
            "config": {"systemPrompt": "测试角色", "rolePrompt": "简洁回答"},
            "runtime_models": {},
        }


class FakeLlm:
    def response(self, _conversation_id, dialogue):
        assert dialogue[0]["role"] == "system"
        return iter(["收到。"])


class FakeTts:
    def to_playground_wav(self, text):
        return b"fake-audio:" + text.encode()


def runtime_token():
    now = int(time.time())
    payload = {
        "v": 1, "aud": "public-conversation", "cid": "conversation-a", "sub": "7",
        "aid": "agent-a", "av": 4, "scopes": ["conversation:text"],
        "in": ["text"], "out": ["text"], "iat": now - 1, "exp": now + 900,
    }
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    ).rstrip(b"=")
    signature = hmac.new(b"runtime-secret", b"v1." + encoded, hashlib.sha256).digest()
    return "v1." + encoded.decode() + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode()


@pytest.mark.asyncio
async def test_external_text_client_crosses_token_bundle_and_websocket_without_mqtt():
    service = PublicConversationService(
        {"manager-api": {"secret": "runtime-secret"}}, FakeRuntimeClient()
    )
    session = await service.open("conversation-a", runtime_token())
    session._llm_factory = lambda _model: FakeLlm()
    session._tts_factory = lambda _model: FakeTts()
    handler = PublicConversationHandler(service)
    app = web.Application()
    app.router.add_get("/api/v1/conversations/{conversation_id}/stream", handler.handle_stream)
    server = TestServer(app)
    client = TestClient(server)
    await client.start_server()
    try:
        ws = await client.ws_connect(
            "/api/v1/conversations/conversation-a/stream",
            headers={"Authorization": "Bearer " + runtime_token()},
        )
        ready = await ws.receive_json()
        assert ready["type"] == "session.ready"
        await ws.send_json({"type": "turn.text", "request_id": "request-a", "text": "你好"})
        events = [await ws.receive_json() for _ in range(3)]
        assert [event["type"] for event in events] == ["turn.started", "llm.delta", "turn.completed"]
        assert [event["sequence"] for event in events] == [2, 3, 4]
        assert all("secret" not in json.dumps(event) for event in events)
        await ws.close()
    finally:
        await client.close()
