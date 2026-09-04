from dataclasses import replace
from decimal import Decimal
import re

from core.capabilities.models import Skill, Trigger
from core.capabilities.router import SkillRouter


def skill(skill_id, triggers, binding_priority=0):
    return Skill(
        id=skill_id,
        version=1,
        package_version=1,
        package_sha256="a" * 64,
        name=skill_id,
        description=skill_id,
        execution_prompt="prompt",
        semantic_threshold=Decimal("0.7"),
        response_mode="LLM",
        timeout_ms=10000,
        failure_message=None,
        binding_priority=binding_priority,
        triggers=tuple(triggers),
        tool_names=(),
        defaults={},
    )


def keyword(value, priority=0, case_sensitive=False):
    return Trigger("KEYWORD", value, priority, case_sensitive, True)


def regex(value, priority=0):
    return Trigger("REGEX", value, priority, False, True, re.compile(value, re.IGNORECASE))


def test_keyword_matching_is_case_insensitive_by_default():
    weather = skill("weather", [keyword("Weather")])

    decision = SkillRouter((weather,)).route("show me the WEATHER")

    assert decision.selected is weather
    assert decision.mode == "RULE"


def test_case_sensitive_keyword_does_not_match_different_case():
    weather = skill("weather", [keyword("Weather", case_sensitive=True)])

    decision = SkillRouter((weather,)).route("weather")

    assert decision.selected is None
    assert decision.semantic_required


def test_regex_priority_longest_match_and_binding_priority_form_the_score():
    short = skill("short", [keyword("天气", priority=10)], binding_priority=1)
    long = skill("long", [regex("上海天气", priority=10)], binding_priority=0)

    decision = SkillRouter((short, long)).route("上海天气怎么样")

    assert decision.selected is long


def test_trigger_priority_outranks_match_length():
    high = skill("high", [keyword("天气", priority=11)])
    long = skill("long", [keyword("上海天气", priority=10)])

    assert SkillRouter((high, long)).route("上海天气").selected is high


def test_negative_example_suppresses_a_rule_match():
    weather = skill(
        "weather",
        [keyword("天气"), Trigger("NEGATIVE_EXAMPLE", "天气不错，陪我聊天", 0, False, True)],
    )

    decision = SkillRouter((weather,)).route("天气不错，陪我聊天")

    assert decision.selected is None
    assert decision.semantic_required


def test_equal_top_scores_escalate_only_the_conflicting_candidates():
    weather = skill("weather", [keyword("今天", priority=10)])
    news = skill("news", [keyword("今天", priority=10)])
    search = skill("search", [keyword("搜索", priority=1)])

    decision = SkillRouter((weather, news, search)).route("今天有什么信息")

    assert decision.selected is None
    assert tuple(item.id for item in decision.candidates) == ("news", "weather")
    assert decision.semantic_required


def test_no_rule_match_escalates_all_skills():
    weather = skill("weather", [keyword("天气")])
    news = skill("news", [keyword("新闻")])

    decision = SkillRouter((weather, news)).route("外面怎么样")

    assert tuple(item.id for item in decision.candidates) == ("weather", "news")
    assert decision.semantic_required
