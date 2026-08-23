from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any

from .protocol import RuntimeTokenClaims


def _decode(value: str) -> bytes:
    try:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except Exception as exc:
        raise ValueError("token encoding is invalid") from exc


def _text(payload: dict[str, Any], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"token claim {key} is invalid")
    return value


def _strings(payload: dict[str, Any], key: str) -> tuple[str, ...]:
    value = payload.get(key)
    if not isinstance(value, list) or not value or not all(
        isinstance(item, str) and item.strip() for item in value
    ):
        raise ValueError(f"token claim {key} is invalid")
    return tuple(item.strip() for item in value)


def verify_runtime_token(token: str, secret: str, now: int | None = None) -> RuntimeTokenClaims:
    if not isinstance(token, str) or not isinstance(secret, str) or not secret:
        raise ValueError("token or secret is invalid")
    parts = token.split(".")
    if len(parts) != 3 or parts[0] != "v1":
        raise ValueError("token format is invalid")
    encoded_payload = parts[1].encode("ascii")
    expected = hmac.new(
        secret.encode("utf-8"),
        b"v1." + encoded_payload,
        hashlib.sha256,
    ).digest()
    if not hmac.compare_digest(expected, _decode(parts[2])):
        raise ValueError("token signature is invalid")
    try:
        payload = json.loads(_decode(parts[1]))
    except Exception as exc:
        raise ValueError("token payload is invalid") from exc
    if not isinstance(payload, dict):
        raise ValueError("token payload is invalid")
    if payload.get("v") != 1 or payload.get("aud") != "public-conversation":
        raise ValueError("token audience or version is invalid")
    if any(key in payload for key in ("api_key", "access_token", "secret", "password", "token")):
        raise ValueError("token contains a secret claim")
    issued_at = payload.get("iat")
    expires_at = payload.get("exp")
    if not isinstance(issued_at, int) or not isinstance(expires_at, int):
        raise ValueError("token timestamps are invalid")
    current = int(time.time()) if now is None else now
    if expires_at <= current or issued_at > current + 60:
        raise ValueError("token is expired or not yet valid")
    agent_version = payload.get("av")
    if not isinstance(agent_version, int) or agent_version <= 0:
        raise ValueError("token agent version is invalid")
    return RuntimeTokenClaims(
        conversation_id=_text(payload, "cid"),
        subject=_text(payload, "sub"),
        agent_id=_text(payload, "aid"),
        agent_version=agent_version,
        scopes=_strings(payload, "scopes"),
        input_modes=_strings(payload, "in"),
        output_modes=_strings(payload, "out"),
        issued_at=issued_at,
        expires_at=expires_at,
    )
