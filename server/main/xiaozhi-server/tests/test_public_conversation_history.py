import time

import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession


class FakeLlm:
    def response(self, _session_id, _dialogue):
        return iter(["远程历史回复"])


@pytest.mark.asyncio
async def test_history_writer_and_loader_are_optional_cross_process_hooks():
    written = []

    async def writer(item):
        written.append(item)

    async def loader(limit):
        assert limit == 10
        return [{"turn_id": "remote-turn", "text": "之前", "reply": "远程", "occurred_at": 1}]

    now = int(time.time())
    session = PublicConversationSession(
        RuntimeTokenClaims("conversation-history", "user-a", "agent-a", 1,
                           ("conversation:text",), ("text",), ("text",), now - 1, now + 900),
        {"conversation_id": "conversation-history", "agent_id": "agent-a", "agent_version": 1,
         "config": {}, "runtime_models": {}},
        llm_factory=lambda _model: FakeLlm(), history_loader=loader, history_writer=writer,
    )

    await session.handle_text(TextTurnInput("request-a", "当前"))
    history = await session.history_async(10)

    assert written[0]["text"] == "当前"
    assert history.details["items"][0]["turn_id"] == "remote-turn"
