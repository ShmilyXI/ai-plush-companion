"""告诉模型现在是什么时候，以及上下文里每段聊天发生在什么时候。

网页会话和设备会话共用：只有开场白或连接建立时知道时间的话，模型会反过来问用户几点了；
上下文里隔了几个小时甚至几天的话不标时间，模型会把以前说的事当成刚刚说的。
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

LOCAL_ZONE = ZoneInfo("Asia/Shanghai")

# 上下文里两句话隔了这么久，就算两次不同的聊天，在后一次开头标上时间。
CHAT_GAP_HOURS = 2

RECENT_MARK = "（刚才）"

_WEEKDAYS = ("周一", "周二", "周三", "周四", "周五", "周六", "周日")


def _day_part(hour: int) -> str:
    if hour < 5:
        return "凌晨"
    if hour < 9:
        return "早上"
    if hour < 12:
        return "上午"
    if hour < 14:
        return "中午"
    if hour < 18:
        return "下午"
    if hour < 23:
        return "晚上"
    return "深夜"


def describe_moment(now: datetime) -> str:
    return f"{now.month}月{now.day}日{_WEEKDAYS[now.weekday()]}{_day_part(now.hour)}{now.hour}点"


def describe_gap(seconds: float) -> str:
    hours = seconds / 3600
    if hours < 20:
        return f"{max(1, round(hours))}个小时"
    days = round(hours / 24)
    if days <= 1:
        return "一天"
    if days < 30:
        return f"{days}天"
    return "一个多月"


def _is_time(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def earlier_chat_mark(occurred_at_ms: Any, now: datetime, gap_hours: float) -> str:
    """给上下文里一段聊天开头加的时间标记。离现在不到 gap_hours 的就是这次在聊的，标“刚才”。"""
    if not _is_time(occurred_at_ms):
        return ""
    age = max(0.0, now.timestamp() - occurred_at_ms / 1000)
    if age < gap_hours * 3600:
        return RECENT_MARK
    return f"（{describe_gap(age)}前）"


def chat_start_marks(times_ms: Sequence[Any], now: datetime, gap_hours: float = CHAT_GAP_HOURS) -> list[str]:
    """按时间顺序给每句话算标记：和前一句隔了 gap_hours 以上的那句是一段聊天的开头，标上时间，其余为空。

    全都是“刚才”的话就是同一次聊天，一个都不标，返回全空。时间未知的句子不标，也不打断前后的分段。
    """
    gap_ms = gap_hours * 3600 * 1000
    previous: Any = None
    marks: list[str] = []
    for at in times_ms:
        if not _is_time(at):
            marks.append("")
            continue
        starts_chat = not _is_time(previous) or at - previous >= gap_ms
        marks.append(earlier_chat_mark(at, now, gap_hours) if starts_chat else "")
        previous = at
    if not any(mark and mark != RECENT_MARK for mark in marks):
        return [""] * len(marks)
    return marks


# 设备端提示词模板里的时间占位符。模板渲染时原样留着，每一轮由 Dialogue 填当时的值。
TIME_PLACEHOLDERS = {
    "current_time": "{{current_time}}",
    "today_date": "{{today_date}}",
    "today_weekday": "{{today_weekday}}",
    "lunar_date": "{{lunar_date}}",
}

_FULL_WEEKDAYS = ("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")


def _lunar_date(now: datetime) -> str:
    try:
        import cnlunar

        lunar = cnlunar.Lunar(now.replace(tzinfo=None), godType="8char")
        return f"{lunar.lunarYearCn}年{lunar.lunarMonthCn[:-1]}{lunar.lunarDayCn}"
    except Exception:
        return "农历获取失败"


def has_time_placeholder(prompt: str) -> bool:
    return any(placeholder in prompt for placeholder in TIME_PLACEHOLDERS.values())


def fill_time_placeholders(prompt: str, now: datetime) -> str:
    """按 now 填模板里的时间占位符。用北京时间：服务端容器的时区是 UTC，直接取系统时间会差 8 小时。"""
    values = {
        "current_time": now.strftime("%H:%M"),
        "today_date": now.strftime("%Y-%m-%d"),
        "today_weekday": _FULL_WEEKDAYS[now.weekday()],
    }
    for key, value in values.items():
        prompt = prompt.replace(TIME_PLACEHOLDERS[key], value)
    if TIME_PLACEHOLDERS["lunar_date"] in prompt:
        prompt = prompt.replace(TIME_PLACEHOLDERS["lunar_date"], _lunar_date(now))
    return prompt


EARLIER_CHAT_NOTE = (
    "对话里用户那句话前面括号里的时间是系统标的，表示那段聊天发生在什么时候。"
    "标着“几天前”“几个小时前”的是以前的聊天，不是用户刚刚说的；用户没有再提起，就不要接着那些旧话题说。"
    "不要在回复里提到这些标记。"
)


def now_context(now: datetime, marked: bool, with_time: bool = True) -> str:
    """每一轮附在系统提示词后面的说明。提示词里已经有时间（设备端模板）时只补标记说明。"""
    lines = [f"现在是{describe_moment(now)}。"] if with_time else []
    if marked:
        lines.append(EARLIER_CHAT_NOTE)
    if not lines:
        return ""
    text = "\n".join(lines)
    return f"\n\n<now>\n{text}\n</now>"
