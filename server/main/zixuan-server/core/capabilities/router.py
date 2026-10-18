from __future__ import annotations

from dataclasses import dataclass

from .models import Skill, Trigger


@dataclass(frozen=True)
class RouteDecision:
    selected: Skill | None
    candidates: tuple[Skill, ...]
    semantic_required: bool
    mode: str


@dataclass(frozen=True)
class _Match:
    skill: Skill
    score: int


class SkillRouter:
    def __init__(self, skills: tuple[Skill, ...] | list[Skill]):
        self._skills = tuple(skills)

    def route(self, utterance: str) -> RouteDecision:
        matches: list[_Match] = []
        for skill in self._skills:
            if self._negative_match(skill, utterance):
                continue
            score = self._best_score(skill, utterance)
            if score is not None:
                matches.append(_Match(skill, score))
        if not matches:
            return RouteDecision(None, self._skills, True, "SEMANTIC")
        matches.sort(key=lambda item: (-item.score, item.skill.id))
        top_score = matches[0].score
        candidates = tuple(item.skill for item in matches if item.score == top_score)
        if len(candidates) == 1:
            return RouteDecision(candidates[0], candidates, False, "RULE")
        return RouteDecision(None, candidates, True, "SEMANTIC")

    def _best_score(self, skill: Skill, utterance: str) -> int | None:
        best = None
        for trigger in skill.triggers:
            if not trigger.enabled or trigger.type not in {"KEYWORD", "REGEX"}:
                continue
            length = self._match_length(trigger, utterance)
            if length <= 0:
                continue
            score = trigger.priority * 1_000_000 + length * 1_000 + skill.binding_priority
            best = score if best is None else max(best, score)
        return best

    def _match_length(self, trigger: Trigger, utterance: str) -> int:
        if trigger.type == "KEYWORD":
            source = utterance if trigger.case_sensitive else utterance.casefold()
            pattern = trigger.value if trigger.case_sensitive else trigger.value.casefold()
            return len(trigger.value) if pattern in source else 0
        if trigger.type == "REGEX" and trigger.regex is not None:
            matched = trigger.regex.search(utterance)
            return len(matched.group(0)) if matched else 0
        return 0

    def _negative_match(self, skill: Skill, utterance: str) -> bool:
        normalized = utterance.casefold()
        for trigger in skill.triggers:
            if trigger.enabled and trigger.type == "NEGATIVE_EXAMPLE":
                pattern = trigger.value if trigger.case_sensitive else trigger.value.casefold()
                source = utterance if trigger.case_sensitive else normalized
                if pattern and pattern in source:
                    return True
        return False
