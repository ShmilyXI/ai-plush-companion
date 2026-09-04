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
