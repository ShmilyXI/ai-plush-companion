import asyncio
import time

import pytest

from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.safety import PLATFORM_SAFETY_POLICY
from core.public_conversation.session import PublicConversationSession, parse_memory_items


class RecordingLlm:
    """回复固定的一句话；被要求提炼记忆时返回一段 JSON。记下每次收到的对话，用来检查提示词。"""

    def __init__(self, extraction='["用户养了一只叫年糕的猫"]'):
        self.extraction = extraction
        self.dialogues = []

    def response(self, _session_id, dialogue):
        self.dialogues.append(dialogue)
        if "只输出一个 JSON 数组" in dialogue[0]["content"]:
            return iter([self.extraction])
        return iter(["年糕这名字真可爱。"])


def session_with(llm, writer=None, config=None):
    now = int(time.time())
    return PublicConversationSession(
        RuntimeTokenClaims("conversation-memory", "user-a", "agent-a", 1,
                           ("conversation:text",), ("text",), ("text",), now - 1, now + 900),
        {"conversation_id": "conversation-memory", "agent_id": "agent-a", "agent_version": 1,
         "config": config or {}, "runtime_models": {}},
        llm_factory=lambda _model: llm, memory_writer=writer,
    )


async def settle(session):
    """等后台提炼记忆的任务跑完。"""
    for _ in range(50):
        if not session._memory_tasks:
            return
        await asyncio.sleep(0.01)


@pytest.mark.asyncio
async def test_what_the_role_remembers_goes_into_the_prompt_along_with_its_name_and_how_to_address_the_user():
    llm = RecordingLlm()
    session = session_with(llm, config={"systemPrompt": "你是用户的朋友。", "profileName": "小秋", "userAddress": "阿远"})
    session.seed_memories(["用户下个月有一场面试", "  ", "用户不吃香菜"])

    await session.handle_text(TextTurnInput("request-a", "今天有点累"))

    system = llm.dialogues[0][0]["content"]
    assert "你的名字是小秋。" in system
    assert "用户希望你这样称呼对方：阿远。" in system
    assert "- 用户下个月有一场面试" in system and "- 用户不吃香菜" in system
    # 平台守则仍然在最后，排在角色设定和记忆之后。
    assert system.index("用户不吃香菜") < system.index(PLATFORM_SAFETY_POLICY.strip())


@pytest.mark.asyncio
async def test_a_turn_adds_what_is_worth_remembering_and_the_next_turn_already_knows_it():
    written = []

    async def writer(items):
        written.append(items)

    llm = RecordingLlm()
    session = session_with(llm, writer)

    await session.handle_text(TextTurnInput("request-a", "我养了一只猫，叫年糕"))
    await settle(session)

    assert written == [["用户养了一只叫年糕的猫"]]
    await session.handle_text(TextTurnInput("request-b", "它今天把杯子打翻了"))
    await settle(session)
    assert "- 用户养了一只叫年糕的猫" in llm.dialogues[-2][0]["content"]
    # 第二轮提炼出来的还是同一件事，不会重复记。
    assert written == [["用户养了一只叫年糕的猫"]]


@pytest.mark.asyncio
async def test_nothing_is_extracted_from_a_short_reply_or_without_somewhere_to_keep_it():
    written = []
    llm = RecordingLlm()
    session = session_with(llm, lambda items: written.append(items))
    await session.handle_text(TextTurnInput("request-a", "嗯嗯"))
    await settle(session)
    assert written == []
    assert len(llm.dialogues) == 1

    # API Key 的会话没有“用户 + 角色”的记忆，不去提炼。
    plain = RecordingLlm()
    session = session_with(plain)
    await session.handle_text(TextTurnInput("request-a", "我养了一只猫，叫年糕"))
    await settle(session)
    assert len(plain.dialogues) == 1


@pytest.mark.asyncio
async def test_a_failed_or_malformed_extraction_never_disturbs_the_conversation():
    def broken(_items):
        raise RuntimeError("manager-api is down")

    session = session_with(RecordingLlm(), broken)
    events = await session.handle_text(TextTurnInput("request-a", "我养了一只猫，叫年糕"))
    await settle(session)
    assert events[-1].event_type == "turn.completed"

    written = []
    session = session_with(RecordingLlm(extraction="我觉得没有什么要记的"), lambda items: written.append(items))
    await session.handle_text(TextTurnInput("request-a", "我养了一只猫，叫年糕"))
    await settle(session)
    assert written == []


def test_parsing_keeps_only_new_short_statements():
    known = ["用户养了一只叫年糕的猫"]
    assert parse_memory_items('好的：["用户养了一只叫年糕的猫。", "用户 在准备面试", 3, ""]', known) == ["用户 在准备面试"]
    assert parse_memory_items("[]", known) == []
    assert parse_memory_items("没有", known) == []
    assert parse_memory_items('{"a": 1}', known) == []
    assert len(parse_memory_items(str(["用户的第%d件事" % i for i in range(9)]).replace("'", '"'), [])) == 5
