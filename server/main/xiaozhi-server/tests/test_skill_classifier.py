import asyncio
from decimal import Decimal
import json

import pytest

from core.capabilities.classifier import SkillClassifier
from core.capabilities.models import Skill, Trigger


def skill(skill_id, threshold="0.7"):
    return Skill(
        id=skill_id,
        version=1,
        name=f"name-{skill_id}",
        description=f"description-{skill_id}",
        execution_prompt="SECRET EXECUTION PROMPT",
        semantic_threshold=Decimal(threshold),
        response_mode="LLM",
        timeout_ms=10000,
        failure_message=None,
        binding_priority=0,
        triggers=(
            Trigger("POSITIVE_EXAMPLE", f"positive-{skill_id}"),
            Trigger("NEGATIVE_EXAMPLE", f"negative-{skill_id}"),
        ),
        tool_names=("secret-tool-schema",),
        defaults={},
    )


class LLM:
    def __init__(self, response):
        self.value = response
        self.calls = []

    def response_no_stream(self, system_prompt, user_prompt, **kwargs):
        self.calls.append((system_prompt, user_prompt, kwargs))
        if isinstance(self.value, Exception):
            raise self.value
        return self.value


@pytest.mark.asyncio
async def test_returns_only_a_valid_candidate_above_its_threshold():
    weather = skill("weather")
    llm = LLM(json.dumps({"skill_id": "weather", "confidence": 0.91}))

    selected = await SkillClassifier(llm).classify("外面怎么样", (weather, skill("news")))

    assert selected is weather
    system, prompt, options = llm.calls[0]
    assert "SECRET EXECUTION PROMPT" not in prompt
    assert "secret-tool-schema" not in prompt
    assert "positive-weather" in prompt
    assert options["max_tokens"] <= 100
    assert options["temperature"] == 0


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "response",
    [
        "not-json",
        json.dumps({"skill_id": "not-a-candidate", "confidence": 0.99}),
        json.dumps({"skill_id": "weather", "confidence": "high"}),
        json.dumps({"skill_id": "weather", "confidence": 0.4}),
        RuntimeError("provider failed"),
    ],
)
async def test_malformed_low_confidence_or_failed_classification_returns_no_skill(response):
    selected = await SkillClassifier(LLM(response)).classify("query", (skill("weather"),))

    assert selected is None


@pytest.mark.asyncio
async def test_timeout_returns_no_skill():
    class SlowLLM:
        def response_no_stream(self, *args, **kwargs):
            import time

            time.sleep(0.1)
            return json.dumps({"skill_id": "weather", "confidence": 1})

    selected = await SkillClassifier(SlowLLM(), timeout_seconds=0.01).classify(
        "query", (skill("weather"),)
    )

    assert selected is None
