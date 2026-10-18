from __future__ import annotations

import asyncio

import pytest

from core.capabilities.models import CapabilityBundle
from core.capabilities.runtime import SkillTurnRuntime


class RuleOnlyClassifier:
    async def classify(self, utterance, candidates):
        raise AssertionError("确定性命中不应调用分类器")


def package_payload():
    return {
        "deviceId": "device-weather",
        "configVersion": 12,
        "skills": [
            {
                "id": "skill-weather",
                "version": 4,
                "packageVersion": 4,
                "packageSha256": "b" * 64,
                "name": "天气查询",
                "description": "查询天气",
                "executionPrompt": "先确认地点，再调用天气工具。",
                "semanticThreshold": 0.7,
                "responseMode": "LLM",
                "timeoutMs": 30000,
                "failureMessage": "天气暂时查不到。",
                "bindingPriority": 10,
                "triggers": [
                    {
                        "type": "KEYWORD",
                        "value": "天气",
                        "priority": 100,
                        "caseSensitive": False,
                        "enabled": True,
                    }
                ],
                "toolNames": ["get_weather"],
                "defaults": {"location": "深圳"},
            }
        ],
        "tools": {
            "get_weather": {
                "name": "get_weather",
                "type": "PLUGIN",
                "refId": "plugin-weather",
                "alias": None,
                "purpose": "查询天气",
                "required": True,
                "defaults": {"location": "深圳"},
                "runtime": {"executor": "weather"},
            },
            "web_search": {
                "name": "web_search",
                "type": "PLUGIN",
                "refId": "plugin-search",
                "alias": None,
                "purpose": "搜索网页",
                "required": False,
                "defaults": {},
                "runtime": {"executor": "search"},
            },
        },
    }


@pytest.mark.asyncio
async def test_package_projection_only_exposes_declared_tools():
    bundle = CapabilityBundle.parse(package_payload())
    turn = await SkillTurnRuntime().select(bundle, "深圳天气怎么样", RuleOnlyClassifier())

    assert turn.skill.id == "skill-weather"
    assert turn.skill.package_version == 4
    assert turn.skill.package_sha256 == "b" * 64
    assert turn.allowed_tool_names == frozenset({"handle_exit_intent", "get_weather"})
    assert "web_search" not in turn.allowed_tool_names


def test_package_projection_preserves_execution_prompt_without_mutating_bundle():
    bundle = CapabilityBundle.parse(package_payload())
    messages = [{"role": "system", "content": "你是陪伴机器人。"}]

    projected = SkillTurnRuntime().inject_execution_prompt(messages, bundle.skills[0])

    assert messages[0]["content"] == "你是陪伴机器人。"
    assert "先确认地点，再调用天气工具。" in projected[0]["content"]


def test_package_payload_can_be_checked_without_audio_or_external_tools():
    bundle = CapabilityBundle.parse(package_payload())
    turn = asyncio.run(SkillTurnRuntime().select(bundle, "深圳天气怎么样", RuleOnlyClassifier()))

    assert turn.bundle.device_id == "device-weather"
    assert turn.bundle.config_version == 12
