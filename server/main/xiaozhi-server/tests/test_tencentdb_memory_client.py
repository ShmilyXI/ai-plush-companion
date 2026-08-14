import json

import httpx
import pytest

from core.providers.memory.tencentdb.client import (
    TencentDbMemoryClient,
    TencentDbMemoryError,
)


ISOLATION = {
    "team_id": "ai-plush-companion:user:7",
    "user_id": "7",
    "agent_id": "profile-a",
}


@pytest.mark.asyncio
async def test_atomic_search_sends_strict_isolation_headers_and_body():
    captured = {}

    def handler(request: httpx.Request):
        captured["request"] = request
        return httpx.Response(200, json={
            "code": 0,
            "message": "ok",
            "request_id": "req-1",
            "data": {"items": [{"id": "a"}]},
        })

    client = TencentDbMemoryClient(
        "http://memory-core:8420", "core-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await client.atomic_search(ISOLATION, "喜欢什么", limit=8)
    finally:
        await client.aclose()

    request = captured["request"]
    assert request.url.path == "/v3/atomic/search"
    assert request.headers["authorization"] == "Bearer core-secret"
    assert request.headers["x-tdai-service-id"] == "ai-plush-companion"
    assert json.loads(request.content) == {
        **ISOLATION,
        "query": "喜欢什么",
        "limit": 8,
    }
    assert result == {"items": [{"id": "a"}], "_request_id": "req-1"}


@pytest.mark.asyncio
async def test_conversation_capture_can_include_device_task_id():
    bodies = []

    def handler(request: httpx.Request):
        bodies.append(json.loads(request.content))
        return httpx.Response(200, json={
            "code": 0,
            "message": "ok",
            "request_id": "req-task",
            "data": {"messages": [], "total": 0},
        })

    client = TencentDbMemoryClient(
        "http://memory-core:8420", "core-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        await client.conversation_add(
            ISOLATION,
            "session-a",
            [{"role": "user", "content": "hello"}],
            task_id="device-a",
        )
        await client.conversation_query(
            ISOLATION,
            session_id="session-a",
            task_id="device-a",
            limit=1,
        )
    finally:
        await client.aclose()

    assert bodies == [
        {
            **ISOLATION,
            "task_id": "device-a",
            "session_id": "session-a",
            "messages": [{"role": "user", "content": "hello"}],
        },
        {
            **ISOLATION,
            "task_id": "device-a",
            "session_id": "session-a",
            "limit": 1,
            "offset": 0,
        },
    ]


@pytest.mark.asyncio
async def test_client_exposes_only_the_frozen_v3_routes():
    paths = []

    def handler(request: httpx.Request):
        paths.append(request.url.path)
        return httpx.Response(200, json={"code": 0, "message": "ok", "request_id": "r", "data": {}})

    client = TencentDbMemoryClient(
        "http://memory-core:8420", "core-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        await client.conversation_add(ISOLATION, "s", [{"role": "user", "content": "hi"}])
        await client.conversation_query(ISOLATION, session_id="s")
        await client.conversation_delete(ISOLATION, message_ids=["m"])
        await client.atomic_query(ISOLATION)
        await client.atomic_search(ISOLATION, "q")
        await client.atomic_update(ISOLATION, "a", "updated")
        await client.atomic_delete(ISOLATION, ["a"])
        await client.scenario_list(ISOLATION)
        await client.scenario_remove(ISOLATION, "/scene/a")
        await client.core_read(ISOLATION)
        await client.core_write(ISOLATION, "profile")
    finally:
        await client.aclose()

    assert paths == [
        "/v3/conversation/add", "/v3/conversation/query", "/v3/conversation/delete",
        "/v3/atomic/query", "/v3/atomic/search", "/v3/atomic/update", "/v3/atomic/delete",
        "/v3/scenario/ls", "/v3/scenario/rm", "/v3/core/read", "/v3/core/write",
    ]


@pytest.mark.asyncio
async def test_client_rejects_missing_isolation_before_network_io():
    calls = 0

    def handler(_: httpx.Request):
        nonlocal calls
        calls += 1
        return httpx.Response(200)

    client = TencentDbMemoryClient(
        "http://memory-core:8420", "core-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(ValueError, match="user_id"):
            await client.atomic_query({"team_id": "team", "agent_id": "agent"})
    finally:
        await client.aclose()
    assert calls == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("status,retryable", [(401, False), (422, False), (503, True)])
async def test_http_errors_are_sanitized_and_classified(status, retryable):
    client = TencentDbMemoryClient(
        "http://memory-core:8420", "core-secret",
        transport=httpx.MockTransport(lambda _: httpx.Response(status, text="secret body")),
    )
    try:
        with pytest.raises(TencentDbMemoryError) as raised:
            await client.atomic_query(ISOLATION)
    finally:
        await client.aclose()

    assert raised.value.status == status
    assert raised.value.retryable is retryable
    assert "secret body" not in str(raised.value)


@pytest.mark.asyncio
async def test_nonzero_envelope_preserves_code_and_request_id_without_data():
    client = TencentDbMemoryClient(
        "http://memory-core:8420", "core-secret",
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json={
            "code": 9001, "message": "bad", "request_id": "req-bad", "data": {"secret": "x"}
        })),
    )
    try:
        with pytest.raises(TencentDbMemoryError) as raised:
            await client.atomic_query(ISOLATION)
    finally:
        await client.aclose()

    assert raised.value.code == 9001
    assert raised.value.request_id == "req-bad"
    assert "secret" not in str(raised.value)
