"""设备端对话：每一轮告诉模型现在的时间，上下文里隔了很久的话标上时间。"""

import time
from datetime import datetime

from core.utils.chat_time import LOCAL_ZONE, fill_time_placeholders
from core.utils.dialogue import Dialogue, Message

HOUR_MS = 3600 * 1000
TEMPLATE = "角色设定\n- Current time: {{current_time}}\n- Today's date: {{today_date}} ({{today_weekday}})\n- Lunar: {{lunar_date}}"


def contents(dialogue):
    return [message["content"] for message in dialogue[1:]]


def test_time_placeholders_are_filled_with_beijing_time_on_every_turn():
    now = datetime(2026, 11, 27, 0, 5, tzinfo=LOCAL_ZONE)
    filled = fill_time_placeholders(TEMPLATE, now)
    assert "Current time: 00:05" in filled
    assert "Today's date: 2026-11-27 (星期五)" in filled
    assert "{{" not in filled

    dialogue = Dialogue()
    dialogue.update_system_message(TEMPLATE)
    dialogue.put(Message(role="user", content="你好"))
    system = dialogue.get_llm_dialogue()[0]["content"]
    assert "{{" not in system
    assert datetime.now(LOCAL_ZONE).strftime("%Y-%m-%d") in system
    # 模板里已经有时间，就不再重复加一段；同一次聊天也不加标记说明。
    assert "<now>" not in system


def test_a_prompt_without_the_template_still_gets_the_time():
    dialogue = Dialogue()
    dialogue.update_system_message("你是一个陪伴角色")
    dialogue.put(Message(role="user", content="现在几点"))
    system = dialogue.get_llm_dialogue()[0]["content"]
    assert "<now>\n现在是" in system
    assert "括号里的时间" not in system


def test_chats_hours_apart_on_one_connection_are_marked():
    now_ms = time.time() * 1000
    dialogue = Dialogue()
    dialogue.update_system_message(TEMPLATE)
    dialogue.put(Message(role="user", content="我明天要面试", created_at=now_ms - 26 * HOUR_MS))
    dialogue.put(Message(role="assistant", content="会顺利的", created_at=now_ms - 26 * HOUR_MS + 5000))
    # 主动陪伴：隔了一天角色先开口，标记顺延到用户这一段的第一句。
    dialogue.put(Message(role="assistant", content="面试怎么样了", created_at=now_ms - 60_000))
    dialogue.put(Message(role="user", content="过了", created_at=now_ms - 30_000))
    dialogue.put(Message(role="user", content="1", is_temporary=False))

    result = dialogue.get_llm_dialogue()
    assert contents(result) == ["（一天前）我明天要面试", "会顺利的", "面试怎么样了", "（刚才）过了", "1"]
    assert "不是用户刚刚说的" in result[0]["content"]
    # 只在给模型的上下文里加标记，对话记录本身不变。
    assert dialogue.dialogue[1].content == "我明天要面试"


def test_one_continuous_chat_is_not_marked():
    dialogue = Dialogue()
    dialogue.update_system_message(TEMPLATE)
    dialogue.put(Message(role="user", content="第一句"))
    dialogue.put(Message(role="assistant", content="回答"))
    dialogue.put(Message(role="user", content="第二句"))
    assert contents(dialogue.get_llm_dialogue()) == ["第一句", "回答", "第二句"]
