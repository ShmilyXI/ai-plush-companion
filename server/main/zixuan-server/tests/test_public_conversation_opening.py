import time
from datetime import datetime

import pytest

from core.public_conversation.opening import (
    LOCAL_ZONE,
    OPENING_CONTEXT_MARK,
    describe_gap,
    describe_moment,
    opening_instruction,
    should_open,
)
from core.public_conversation.protocol import RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import PublicConversationSession

HOUR_MS = 3600 * 1000


def claims():
    return RuntimeTokenClaims("conversation-a", "7", "agent-a", 4, ("conversation:text",),
                              ("text",), ("text", "audio"), 0, 2**31)


class ScriptedLlm:
    def __init__(self, chunks):
        self.chunks = chunks
        self.dialogues = []
        self.last_usage = {"prompt_tokens": 90, "completion_tokens": 8}

    def response(self, _session_id, dialogue):
        self.dialogues.append(dialogue)
        if isinstance(self.chunks, Exception):
            raise self.chunks
        return iter(self.chunks)


class FakeTts:
    def __init__(self):
        self.spoken = []

    def to_playground_wav(self, text):
        self.spoken.append(text)
        return b"WAV:" + text.encode()


def make_session(chunks, *, gap_hours=4, restored=None):
    llm = ScriptedLlm(chunks)
    tts = FakeTts()
    written = []
    reports = []

    async def writer(item):
        written.append(item)
        return {"remaining_turns": 150}

    async def reporter(event):
        reports.append(event)

    config = {"systemPrompt": "你是一个测试角色", "historyThread": True}
    if gap_hours is not None:
        config["openingGapHours"] = gap_hours
    session = PublicConversationSession(
        claims(),
        {"conversation_id": "conversation-a", "agent_id": "agent-a", "agent_version": 4,
         "config": config, "runtime_models": {}},
        llm_factory=lambda _model: llm, tts_factory=lambda _model: tts,
        history_writer=writer, safety_reporter=reporter,
    )
    if restored is not None:
        session.seed_history(restored)
    return session, llm, tts, written, reports


def earlier(hours):
    return [{"turn_id": "t1", "text": "我明天要去面试，有点紧张", "reply": "你准备了这么久，会顺利的。",
             "occurred_at": int(time.time() * 1000) - hours * HOUR_MS}]


async def open_page(session):
    turn_id, events = session.begin_opening("opening-1")
    if not turn_id:
        return turn_id, events
    return turn_id, await session.finish_opening(turn_id, events)


def reply_of(events):
    return "".join(event.details["text"] for event in events if event.event_type == "llm.delta")


def test_whether_to_speak_first_depends_on_how_long_it_has_been():
    now = 1_000 * HOUR_MS
    assert should_open(None, False, 4, now), "a first meeting gets a greeting"
    assert should_open(now - 5 * HOUR_MS, True, 4, now)
    assert not should_open(now - 3 * HOUR_MS, True, 4, now), "a reload or reconnect is not a new visit"
    assert not should_open(now - 50 * HOUR_MS, True, 0, now), "0 turns the feature off"
    assert not should_open(None, True, 4, now), "unknown time of the last turn: stay quiet"


def test_the_model_is_told_the_occasion_not_what_to_say():
    now = datetime(2026, 11, 20, 21, 30, tzinfo=LOCAL_ZONE)
    assert describe_moment(now) == "11月20日周五晚上21点"
    assert describe_gap(5 * 3600) == "5个小时"
    assert describe_gap(26 * 3600) == "一天"
    assert describe_gap(3 * 24 * 3600) == "3天"
    first = opening_instruction(None, now)
    assert "第一次见面" in first and "11月20日周五晚上21点" in first
    again = opening_instruction(now.timestamp() * 1000 - 26 * HOUR_MS, now)
    assert "已经过去一天" in again and "后来怎么样了" in again


@pytest.mark.asyncio
async def test_a_returning_user_is_greeted_with_a_follow_up_that_joins_the_record():
    session, llm, tts, written, _ = make_session(["面试", "怎么样了？"], restored=earlier(20))

    turn_id, events = await open_page(session)

    assert [event.event_type for event in events] == [
        "turn.started", "llm.delta", "llm.delta", "tts.audio", "turn.completed"]
    assert events[0].details["input_mode"] == "opening"
    assert reply_of(events) == "面试怎么样了？"
    assert tts.spoken == ["面试怎么样了？"]
    # 模型看到了之前的对话，最后一条是系统给的场合说明，不是用户的话。
    dialogue = llm.dialogues[0]
    assert dialogue[1]["content"] == "我明天要去面试，有点紧张"
    assert dialogue[-1]["role"] == "user" and "系统给你的提示" in dialogue[-1]["content"]
    # 写进记录的是角色说的那句，没有用户的话；用量按开场白计，不算用户的一轮。
    assert written == [{
        "turn_id": turn_id, "text": "", "reply": "面试怎么样了？", "occurred_at": written[0]["occurred_at"],
        "opening": True, "usage": written[0]["usage"],
    }]
    assert written[0]["usage"]["input_mode"] == "opening"
    assert written[0]["usage"]["input_chars"] == 0
    assert written[0]["usage"]["prompt_tokens"] == 90
    assert session._completed_turns == set()


@pytest.mark.asyncio
async def test_the_next_turn_knows_what_the_role_just_said():
    session, llm, _, _, _ = make_session(["面试怎么样了？"], restored=earlier(20))
    await open_page(session)
    llm.chunks = ["太好了！"]

    await session.handle_text(TextTurnInput("request-a", "过了"))

    dialogue = llm.dialogues[1]
    assert [message["role"] for message in dialogue] == ["system", "user", "assistant", "user", "assistant", "user"]
    assert dialogue[3]["content"] == OPENING_CONTEXT_MARK
    assert dialogue[4]["content"] == "面试怎么样了？"
    assert dialogue[5]["content"] == "过了"


@pytest.mark.asyncio
async def test_the_time_of_the_last_turn_is_understood_when_the_manager_sends_it_as_text():
    # manager-api 把长整数序列化成字符串。
    stored = earlier(20)
    stored[0]["occurred_at"] = str(stored[0]["occurred_at"])
    session, _, _, written, _ = make_session(["面试怎么样了？"], restored=stored)

    await open_page(session)

    assert len(written) == 1


@pytest.mark.asyncio
async def test_a_first_meeting_gets_a_greeting():
    session, llm, _, written, _ = make_session(["你好呀，今天过得怎么样？"], restored=[])

    _, events = await open_page(session)

    assert reply_of(events) == "你好呀，今天过得怎么样？"
    assert "第一次见面" in llm.dialogues[0][-1]["content"]
    assert len(written) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("why, kwargs", [
    ("a reload shortly after the last turn", {"restored": earlier(1)}),
    ("the feature is switched off", {"restored": earlier(50), "gap_hours": 0}),
    ("no setting from the manager", {"restored": earlier(50), "gap_hours": None}),
    ("the stored conversation could not be loaded", {"restored": None}),
])
async def test_the_role_stays_quiet_when_it_should(why, kwargs):
    session, llm, _, written, _ = make_session(["不该说的话"], **kwargs)

    turn_id, events = await open_page(session)

    assert (turn_id, events, llm.dialogues, written) == ("", [], [], []), why


@pytest.mark.asyncio
async def test_only_the_first_request_in_a_session_counts_and_never_after_the_user_spoke():
    session, llm, _, _, _ = make_session(["面试怎么样了？"], restored=earlier(20))
    await open_page(session)
    assert session.begin_opening("opening-2") == ("", [])

    talking, llm_b, _, _, _ = make_session(["嗯。"], restored=earlier(20))
    await talking.handle_text(TextTurnInput("request-a", "我回来了"))
    assert talking.begin_opening("opening-1") == ("", [])
    assert len(llm_b.dialogues) == 1

    # 上一次打开时已经说过开场白：它进了记录，所以紧接着再打开不会重复。
    reopened = earlier(20) + [{"turn_id": "t2", "text": "", "reply": "面试怎么样了？", "opening": True,
                               "occurred_at": int(time.time() * 1000) - 60_000}]
    again, llm_c, _, _, _ = make_session(["不该说的话"], restored=reopened)
    assert again.begin_opening("opening-1") == ("", [])
    assert llm_c.dialogues == []


@pytest.mark.asyncio
async def test_a_failed_opening_ends_quietly_without_showing_an_error():
    session, _, _, written, _ = make_session(RuntimeError("upstream down"), restored=earlier(20))

    _, events = await open_page(session)

    assert [event.event_type for event in events] == ["turn.started", "turn.cancelled"]
    assert events[-1].details["reason"] == "opening_failed"
    assert written == [] and session._active_turns == set()
    # 之后用户照常说话。
    session._llm.chunks = ["我在。"]
    later = await session.handle_text(TextTurnInput("request-a", "在吗"))
    assert later[-1].event_type == "turn.completed"


@pytest.mark.asyncio
async def test_an_unacceptable_opening_is_taken_back_and_not_recorded():
    session, _, tts, written, reports = make_session(["你好，", "她呻吟着……"], restored=earlier(20))

    _, events = await open_page(session)

    kinds = [event.event_type for event in events]
    assert kinds[-2:] == ["reply.withdrawn", "turn.cancelled"]
    withdrawn = next(event for event in events if event.event_type == "reply.withdrawn")
    assert withdrawn.details["text"] == ""
    assert written == [] and tts.spoken == []
    assert reports[0]["direction"] == "output"


@pytest.mark.asyncio
async def test_interrupting_a_finished_opening_is_not_an_error():
    session, _, _, _, _ = make_session(["面试怎么样了？"], restored=earlier(20))
    turn_id, _ = await open_page(session)

    events = await session.cancel(turn_id)

    # 和打断一轮已经说完的普通对话一样处理，而不是报“只能取消当前活跃轮次”。
    assert [event.details["code"] for event in events] == ["duplicate_request"]
