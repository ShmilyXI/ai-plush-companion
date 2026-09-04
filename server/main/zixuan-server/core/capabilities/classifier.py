from __future__ import annotations

import asyncio
import json

from .models import Skill


class SkillClassifier:
    def __init__(self, llm, *, timeout_seconds: float = 2.0):
        self._llm = llm
        self._timeout = timeout_seconds

    async def classify(self, utterance: str, candidates: tuple[Skill, ...] | list[Skill]) -> Skill | None:
        values = tuple(candidates)
        if not values:
            return None
        by_id = {skill.id: skill for skill in values}
        prompt = json.dumps(
            {
                "utterance": utterance,
                "candidates": [self._candidate(skill) for skill in values],
                "output": {"skill_id": "candidate id or null", "confidence": "number 0..1"},
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        system = "你是技能路由分类器。只输出一个 JSON 对象，不解释，不调用工具。"
        try:
            response = await asyncio.wait_for(
                asyncio.to_thread(
                    self._llm.response_no_stream,
                    system,
                    prompt,
                    max_tokens=80,
                    temperature=0,
                ),
                timeout=self._timeout,
            )
            parsed = json.loads(response)
            if not isinstance(parsed, dict) or set(parsed) != {"skill_id", "confidence"}:
                return None
            skill_id = parsed.get("skill_id")
            confidence = parsed.get("confidence")
            if not isinstance(skill_id, str) or isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
                return None
            selected = by_id.get(skill_id)
            if selected is None or not 0 <= confidence <= 1:
                return None
            if confidence < float(selected.semantic_threshold):
                return None
            return selected
        except Exception:
            return None

    def _candidate(self, skill: Skill) -> dict:
        positive = [trigger.value for trigger in skill.triggers if trigger.type == "POSITIVE_EXAMPLE"]
        negative = [trigger.value for trigger in skill.triggers if trigger.type == "NEGATIVE_EXAMPLE"]
        return {
            "id": skill.id,
            "name": skill.name,
            "description": skill.description,
            "positive_examples": positive,
            "negative_examples": negative,
        }
