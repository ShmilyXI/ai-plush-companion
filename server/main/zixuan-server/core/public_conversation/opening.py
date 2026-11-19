"""打开即跟进：用户隔了一段时间再打开页面时，角色先开口。

陪伴类产品里，用户回来时看到一个空白的输入框，和看到“面试怎么样了？”是两种感受。
这里只决定两件事：这一次该不该开口，以及怎么告诉模型现在是什么场合。
开口说什么由模型根据之前的对话决定。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

LOCAL_ZONE = ZoneInfo("Asia/Shanghai")

# 开场白在给模型的上下文里占一轮。有些模型要求用户和助手的消息严格交替，
# 所以用这句话占住“用户”的位置，而不是让两条助手消息挨在一起。
OPENING_CONTEXT_MARK = "（用户打开了页面，还没有说话）"

_WEEKDAYS = ("周一", "周二", "周三", "周四", "周五", "周六", "周日")


def gap_hours_setting(config: Any) -> int:
    """运营配置的间隔小时数。没有配置、配置成 0 或写错了，都表示不主动开口。"""
    value = config.get("openingGapHours") if hasattr(config, "get") else None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        return 0
    return int(value)


def should_open(last_occurred_at_ms: Any, has_history: bool, gap_hours: int, now_ms: float) -> bool:
    """该不该开口。

    没聊过：开口打个招呼。聊过：距离上一句话超过设定的间隔才开口，
    否则用户只是刷新了页面或者断线重连，接着聊就行，不需要再打一次招呼。
    """
    if gap_hours <= 0:
        return False
    if not has_history:
        return True
    if isinstance(last_occurred_at_ms, bool) or not isinstance(last_occurred_at_ms, (int, float)):
        # 不知道上次是什么时候，宁可不说。
        return False
    return now_ms - last_occurred_at_ms >= gap_hours * 3600 * 1000


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


def opening_instruction(last_occurred_at_ms: Any, now: datetime) -> str:
    """作为这一轮的“用户消息”交给模型的说明。它不进对话记录，用户也看不到。"""
    moment = describe_moment(now)
    if isinstance(last_occurred_at_ms, bool) or not isinstance(last_occurred_at_ms, (int, float)):
        return (
            "（这不是用户说的话，是系统给你的提示。）\n"
            f"这是你和用户第一次见面。他刚打开页面，还没有说话，现在是{moment}。\n"
            "请你先开口：用一两句话打个招呼，让他知道可以随便聊，再问一个很容易回答的小问题。\n"
            "不超过 40 个字。不要长篇自我介绍，不要列举你能做什么。"
        )
    gap = describe_gap(max(0.0, now.timestamp() - last_occurred_at_ms / 1000))
    return (
        "（这不是用户说的话，是系统给你的提示。）\n"
        f"用户刚打开页面，还没有说话。距离你们上次聊天已经过去{gap}，现在是{moment}。\n"
        "请你先开口说一两句话，不超过 40 个字：\n"
        "- 上面的对话里如果有值得接着问的事（他说过要去做的事、在等的结果、放不下的心事），"
        "自然地问一句后来怎么样了，只挑一件。\n"
        "- 如果他上次情绪很低落，先关心他现在好不好，不追问细节。\n"
        "- 没有合适的事可以接，就简单打个招呼，说一句和现在这个时间有关的话。\n"
        "不要复述上次聊过的内容，不要说“根据我们上次的聊天”这类话，不要一次问好几个问题。"
    )
