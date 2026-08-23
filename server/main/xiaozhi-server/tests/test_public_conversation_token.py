import base64
import hashlib
import hmac
import json
import time

import pytest

from core.public_conversation.token import verify_runtime_token


def make_token(payload, secret="runtime-secret"):
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    ).rstrip(b"=")
    signature = hmac.new(secret.encode(), b"v1." + encoded, hashlib.sha256).digest()
    return "v1." + encoded.decode() + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode()


def valid_payload(**overrides):
    now = int(time.time())
    payload = {
        "v": 1,
        "aud": "public-conversation",
        "cid": "conversation-a",
        "sub": "user-a",
        "aid": "agent-a",
        "av": 4,
        "scopes": ["conversation:text"],
        "in": ["text"],
        "out": ["text"],
        "iat": now - 1,
        "exp": now + 900,
    }
    payload.update(overrides)
    return payload


def test_verifies_java_compatible_claims():
    claims = verify_runtime_token(make_token(valid_payload()), "runtime-secret")

    assert claims.conversation_id == "conversation-a"
    assert claims.agent_version == 4
    assert claims.scopes == ("conversation:text",)


@pytest.mark.parametrize(
    "payload",
    [
        valid_payload(exp=1),
        valid_payload(aud="other-service"),
        valid_payload(v=2),
        valid_payload(cid=""),
        valid_payload(api_key="secret-value"),
    ],
)
def test_rejects_invalid_or_secret_bearing_claims(payload):
    with pytest.raises(ValueError):
        verify_runtime_token(make_token(payload), "runtime-secret")


def test_rejects_tampered_signature():
    token = make_token(valid_payload())
    tampered = token[:-1] + ("A" if token[-1] != "A" else "B")

    with pytest.raises(ValueError, match="signature"):
        verify_runtime_token(tampered, "runtime-secret")
