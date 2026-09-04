import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession


class Memory:
    def __init__(self):
        self.queries = 0
        self.saves = 0

    def init_memory(self, *_args, **_kwargs):
        pass

    async def query_memory(self, _text):
        self.queries += 1
        return "should not be used"

    async def save_memory(self, *_args, **_kwargs):
        self.saves += 1


class Llm:
    def response(self, _conversation_id, _dialogue):
        return iter(["回复"])


@pytest.mark.asyncio
async def test_memory_disabled_skips_recall_and_automatic_write():
    import time

    now = int(time.time())
    memory = Memory()
    session = PublicConversationSession(
        RuntimeTokenClaims("conversation-a", "user-a", "agent-a", 1, ("conversation:text",), ("text",), ("text",), now - 1, now + 60),
        {
            "conversation_id": "conversation-a",
            "agent_id": "agent-a",
            "agent_version": 1,
            "config": {"memoryEnabled": False},
            "runtime_models": {"Memory": {"type": "fake"}},
        },
        llm_factory=lambda _model: Llm(),
    )
    session._memory_factory = lambda _model: memory

    events = await session.handle_text(TextTurnInput("request-a", "你好"))

    assert events[-1].event_type == "turn.completed"
    assert memory.queries == 0
    assert memory.saves == 0
