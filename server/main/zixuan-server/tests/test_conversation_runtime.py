import asyncio

import pytest

from core.conversation.contract import ConversationInput, ConversationRequest
from core.conversation.runtime import ConversationRuntime


async def collect_until_closed(handle):
    values = []
    async for event in handle.events:
        values.append(event)
        if event.kind == "session.closed":
            break
    return values


@pytest.mark.asyncio
async def test_runtime_emits_ordered_lifecycle_events_and_captures_bundle_per_turn():
    request = ConversationRequest(
        user_id="user-a",
        profile_id="profile-a",
        conversation_id="conversation-a",
        source="app",
        input_mode="text",
        output_mode="text",
    )
    runtime = ConversationRuntime()
    handle = await runtime.start(request)

    await handle.send(ConversationInput(kind="text", request_id="request-a", text="你好"))
    await asyncio.sleep(0)
    await handle.close()
    events = await collect_until_closed(handle)

    assert [event.kind for event in events] == [
        "session.started", "turn.started", "llm.delta", "turn.completed", "session.closed"
    ]
    assert [event.sequence for event in events] == list(range(1, len(events) + 1))
    assert events[1].details["profile_id"] == "profile-a"


@pytest.mark.asyncio
async def test_cancel_reports_reason_once_and_closes_active_turn():
    async def processor(_request, _input, emit):
        await emit("llm.delta", {"text": "partial"})
        await asyncio.sleep(1)

    request = ConversationRequest(
        user_id=None, profile_id="profile-a", conversation_id="conversation-a",
        source="device", input_mode="text", output_mode="text",
    )
    handle = await ConversationRuntime(processor=processor).start(request)
    await handle.send(ConversationInput(kind="text", request_id="request-a", text="你好"))
    await asyncio.sleep(0)
    turn_id = next(event.turn_id for event in list(handle.pending_events) if event.kind == "turn.started")
    await handle.cancel("user_interrupted")
    await handle.close()
    events = await collect_until_closed(handle)

    cancelled = [event for event in events if event.kind == "turn.cancelled"]
    assert len(cancelled) == 1
    assert cancelled[0].turn_id == turn_id
    assert cancelled[0].details["reason"] == "user_interrupted"
