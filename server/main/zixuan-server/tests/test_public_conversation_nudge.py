import asyncio
import base64
import json
import time

import pytest

from core.public_conversation.protocol import AudioTurnInput, RuntimeTokenClaims, TextTurnInput
from core.public_conversation.session import MAX_UNANSWERED_NUDGES, PublicConversationSession


class PlannerLlm:
    """普通对话回一句固定的话；被问到要不要主动开口时，按 plan 的设定回答。"""

    def __init__(self, plan=None):
        self.plan = plan or {"action": "speak", "text": "刚才说到的面试，准备得怎么样了？", "emotion": "neutral", "reason_code": "follow_up"}
        self.planner_prompts = []

    def response(self, _session_id, dialogue, **_kwargs):
        if "action、text、emotion、reason_code" in dialogue[0]["content"]:
            self.planner_prompts.append(dialogue)
            return iter([json.dumps(self.plan, ensure_ascii=False)])
        return iter(["好呀。"])

    def response_no_stream(self, system_prompt, user_prompt, **kwargs):
        return "".join(self.response("", [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}]))


class PunctuationAsr:
    async def to_playground_text(self, _pcm):
        return "，"


def session_with(llm, *, first_party=True, asr=None):
    written = []

    async def writer(item):
        written.append(item)

    now = int(time.time())
    session = PublicConversationSession(
        RuntimeTokenClaims("conversation-nudge", "user-a", "agent-a", 1,
                           ("conversation:text", "conversation:audio"), ("text", "audio"), ("text",), now - 1, now + 900),
        {"conversation_id": "conversation-nudge", "agent_id": "agent-a", "agent_version": 1,
         "config": {"historyThread": first_party, "profileName": "小满", "rolePrompt": "开朗的朋友"},
         "runtime_models": {"ASR": {}}},
        llm_factory=lambda _model: llm, asr_factory=lambda _model: asr, history_writer=writer,
    )
    return session, written


def types(events):
    return [event.event_type for event in events]


@pytest.mark.asyncio
async def test_after_a_quiet_spell_the_role_brings_something_up_without_using_up_the_daily_turns():
    llm = PlannerLlm()
    session, written = session_with(llm)
    await session.handle_text(TextTurnInput("request-a", "我下周有个面试"))

    events = await session.nudge("nudge-1", 35, [])

    assert types(events)[0] == "turn.started"
    assert events[0].details["input_mode"] == "opening"
    assert [event.details["text"] for event in events if event.event_type == "llm.delta"] == ["刚才说到的面试，准备得怎么样了？"]
    assert events[-1].event_type == "turn.completed" and events[-1].details["proactive"] is True
    # 记进对话记录，用户那一栏为空；用量按开场白那一类记，不占每日次数。
    assert written[-1]["text"] == "" and written[-1]["opening"] is True
    assert written[-1]["usage"]["input_mode"] == "opening"
    # 规划时看到了角色的名字和最近聊的内容。
    planner_prompt = llm.planner_prompts[0]
    assert "小满" in planner_prompt[0]["content"]
    assert "我下周有个面试" in planner_prompt[1]["content"]


@pytest.mark.asyncio
async def test_the_role_stays_quiet_when_it_has_nothing_natural_to_say():
    session, written = session_with(PlannerLlm({"action": "silent", "text": "", "emotion": "neutral", "reason_code": "nothing"}))
    assert await session.nudge("nudge-1", 35, []) == []
    assert written == []


@pytest.mark.asyncio
async def test_it_gives_up_after_a_few_unanswered_tries_and_starts_again_once_the_user_talks():
    session, _ = session_with(PlannerLlm())
    for index in range(MAX_UNANSWERED_NUDGES):
        session._last_nudge_at = 0
        assert types(await session.nudge(f"nudge-{index}", 60, []))[-1] == "turn.completed"
    session._last_nudge_at = 0
    assert await session.nudge("nudge-extra", 60, []) == []

    await session.handle_text(TextTurnInput("request-a", "我回来了"))
    session._last_nudge_at = 0
    assert types(await session.nudge("nudge-after", 60, []))[-1] == "turn.completed"


@pytest.mark.asyncio
async def test_it_does_not_speak_too_often_for_api_key_callers_or_after_the_user_starts_talking():
    session, _ = session_with(PlannerLlm())
    assert types(await session.nudge("nudge-1", 35, []))[-1] == "turn.completed"
    # 刚说过一次，隔得太近。
    assert await session.nudge("nudge-2", 35, []) == []

    third_party, _ = session_with(PlannerLlm(), first_party=False)
    assert await third_party.nudge("nudge-1", 35, []) == []

    # 想的过程中用户开口了：想完也不说。
    class SlowPlanner(PlannerLlm):
        def response(self, session_id, dialogue, **kwargs):
            if "action、text、emotion、reason_code" in dialogue[0]["content"]:
                time.sleep(0.2)
            return super().response(session_id, dialogue, **kwargs)

    slow, _ = session_with(SlowPlanner())
    pending = asyncio.create_task(slow.nudge("nudge-1", 35, []))
    await asyncio.sleep(0.05)
    await slow.handle_text(TextTurnInput("request-a", "等一下，我想到一件事"))
    assert await pending == []


@pytest.mark.asyncio
async def test_noise_recognised_as_a_punctuation_mark_is_not_answered_and_shows_no_error():
    llm = PlannerLlm()
    session, written = session_with(llm, asr=PunctuationAsr())
    item = AudioTurnInput("request-a", 0, base64.b64encode(b"\x00\x00" * 1600).decode("ascii"), True, 100)

    events = await session.handle_audio(item)

    assert types(events) == ["turn.started", "turn.cancelled"]
    assert events[-1].details["reason"] == "no_speech"
    assert written == []
    assert llm.planner_prompts == []


@pytest.mark.asyncio
async def test_sound_without_anyone_speaking_never_reaches_the_recogniser():
    class CountingAsr:
        calls = 0

        async def to_playground_text(self, _pcm):
            CountingAsr.calls += 1
            return "The."

    llm = PlannerLlm()
    session, written = session_with(llm, asr=CountingAsr())
    session._speech_check = lambda _pcm: False
    item = AudioTurnInput("request-a", 0, base64.b64encode(b"\x00\x00" * 1600).decode("ascii"), True, 100)

    events = await session.handle_audio(item)

    assert types(events) == ["turn.started", "turn.cancelled"]
    assert events[-1].details["reason"] == "no_speech"
    assert CountingAsr.calls == 0
    assert written == []


def test_the_speech_check_tells_a_voice_apart_from_noise_and_a_tone():
    import numpy as np

    from core.public_conversation.speech_check import speech_ms

    if speech_ms(b"\x00\x00" * 512) is None:
        pytest.skip("人声检测模型不在这台机器上")
    rate = 16000
    t = np.arange(rate) / rate
    tone = (0.5 * np.sin(2 * np.pi * 220 * t) * 32767).astype(np.int16).tobytes()
    noise = (np.random.default_rng(1).normal(0, 0.15, rate) * 32767).clip(-32768, 32767).astype(np.int16).tobytes()
    silence = b"\x00\x00" * rate
    for pcm in (tone, noise, silence):
        assert speech_ms(pcm) < 200
