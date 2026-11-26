import base64
import hashlib
import hmac
import json
import time

import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.service import PublicConversationService
from core.public_conversation.session import PublicConversationSession
from core.public_conversation.token import verify_runtime_token


def token_for(claims):
    payload = {
        "v": 1, "aud": "public-conversation", "cid": claims.conversation_id,
        "sub": claims.subject, "aid": claims.agent_id, "av": claims.agent_version,
        "scopes": list(claims.scopes), "in": list(claims.input_modes),
        "out": list(claims.output_modes), "iat": claims.issued_at, "exp": claims.expires_at,
    }
    encoded = base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()).rstrip(b"=")
    signature = hmac.new(b"runtime-secret", b"v1." + encoded, hashlib.sha256).digest()
    return "v1." + encoded.decode() + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode()


class FakeRuntimeClient:
    async def bundle(self, conversation_id):
        return {
            "conversationId": conversation_id,
            "agentId": "agent-a",
            "agentVersion": 4,
            "config": {"systemPrompt": "测试角色"},
            "runtimeModels": {},
        }


@pytest.mark.asyncio
async def test_java_style_token_and_runtime_bundle_share_agent_identity():
    now = int(time.time())
    claims = RuntimeTokenClaims("conversation-a", "user-a", "agent-a", 4, ("conversation:text",), ("text",), ("text",), now - 1, now + 900)
    token = token_for(claims)
    restored = verify_runtime_token(token, "runtime-secret")
    service = PublicConversationService({"manager-api": {"secret": "runtime-secret"}}, FakeRuntimeClient())

    session = await service.open("conversation-a", token)

    assert restored.agent_id == session.claims.agent_id == "agent-a"
    assert restored.agent_version == session.claims.agent_version == 4


@pytest.mark.asyncio
async def test_reopening_existing_session_rejects_mismatched_subject_or_permissions():
    now = int(time.time())
    first_claims = RuntimeTokenClaims(
        "conversation-a", "user-a", "agent-a", 4,
        ("conversation:text",), ("text",), ("text",), now - 1, now + 900,
    )
    second_claims = RuntimeTokenClaims(
        "conversation-a", "user-b", "agent-a", 4,
        ("conversation:audio",), ("audio",), ("audio",), now - 1, now + 900,
    )
    service = PublicConversationService({"manager-api": {"secret": "runtime-secret"}}, FakeRuntimeClient())

    await service.open("conversation-a", token_for(first_claims))

    with pytest.raises(ValueError, match="runtime token does not match session"):
        await service.open("conversation-a", token_for(second_claims))


class ThreadRuntimeClient(FakeRuntimeClient):
    """A manager-api that remembers what this user and role said in earlier sessions."""

    def __init__(self, *, enabled=True, fail=False):
        self.enabled = enabled
        self.fail = fail
        self.requested = []
        self.written = []

    async def bundle(self, conversation_id):
        bundle = await super().bundle(conversation_id)
        bundle["config"] = {"systemPrompt": "测试角色", "historyThread": self.enabled}
        return bundle

    async def thread(self, conversation_id, limit):
        self.requested.append((conversation_id, limit))
        if self.fail:
            raise RuntimeError("manager-api unavailable")
        return [
            {"turn_id": "old-1", "text": "我的猫叫年糕", "reply": "年糕这名字真可爱", "occurred_at": 1},
            {"turn_id": "old-2", "text": "", "reply": "不完整的一轮"},
        ]

    async def append_history(self, conversation_id, item):
        self.written.append(item)


class RecordingLlm:
    tools_enabled = False

    def __init__(self):
        self.dialogues = []

    def response(self, _session_id, dialogue):
        self.dialogues.append([dict(message) for message in dialogue])
        return iter(["它叫年糕。"])


async def open_text_session(client):
    now = int(time.time())
    claims = RuntimeTokenClaims("conversation-b", "user-a", "agent-a", 4,
                                ("conversation:text",), ("text",), ("text",), now - 1, now + 900)
    service = PublicConversationService({"manager-api": {"secret": "runtime-secret"}}, client)
    session = await service.open("conversation-b", token_for(claims))
    llm = RecordingLlm()
    session._llm_factory = lambda _model: llm
    return session, llm


@pytest.mark.asyncio
async def test_a_new_session_continues_from_what_was_said_in_earlier_sessions():
    client = ThreadRuntimeClient()
    session, llm = await open_text_session(client)

    await session.handle_text(TextTurnInput("request-a", "我的猫叫什么？"))

    assert client.requested == [("conversation-b", 20)]
    assert [(message["role"], message["content"]) for message in llm.dialogues[0][1:]] == [
        ("user", "（一个多月前）我的猫叫年糕"), ("assistant", '{"emotion":"neutral"}\n年糕这名字真可爱'), ("user", "我的猫叫什么？"),
    ]
    # 之前的对话只是上下文：不会再写回存储，写回的只有这一次新产生的一轮。
    assert [item["text"] for item in client.written] == ["我的猫叫什么？"]


@pytest.mark.asyncio
async def test_sessions_without_a_thread_or_with_a_failing_store_still_start():
    disabled = ThreadRuntimeClient(enabled=False)
    session, llm = await open_text_session(disabled)
    await session.handle_text(TextTurnInput("request-a", "你好"))
    assert disabled.requested == []
    assert [message["role"] for message in llm.dialogues[0]] == ["system", "user"]

    failing = ThreadRuntimeClient(fail=True)
    session, llm = await open_text_session(failing)
    events = await session.handle_text(TextTurnInput("request-a", "你好"))
    assert events[-1].event_type == "turn.completed"
    assert [message["role"] for message in llm.dialogues[0]] == ["system", "user"]
