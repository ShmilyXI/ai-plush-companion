import copy

import pytest

from core.capabilities.client import CapabilityBundleClient, CapabilityBundleError


def payload():
    return {
        "deviceId": "device-1",
        "configVersion": 7,
        "skills": [
            {
                "id": "skill-weather",
                "version": 2,
                "name": "天气查询",
                "description": "查询天气",
                "executionPrompt": "调用天气工具",
                "semanticThreshold": 0.7,
                "responseMode": "LLM",
                "timeoutMs": 10000,
                "failureMessage": "查询失败",
                "bindingPriority": 5,
                "triggers": [
                    {
                        "type": "KEYWORD",
                        "value": "天气",
                        "priority": 10,
                        "caseSensitive": False,
                        "enabled": True,
                    }
                ],
                "toolNames": ["get_weather"],
                "defaults": {"location": "杭州"},
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
                "defaults": {"location": "杭州"},
            }
        },
    }


@pytest.mark.asyncio
async def test_parses_a_strict_immutable_device_bundle():
    client = CapabilityBundleClient(lambda device_id: async_value(payload()))

    bundle = await client.fetch("device-1")

    assert bundle.device_id == "device-1"
    assert bundle.config_version == 7
    assert bundle.skills[0].tool_names == ("get_weather",)
    with pytest.raises(TypeError):
        bundle.tools["other"] = bundle.tools["get_weather"]


@pytest.mark.asyncio
async def test_accepts_long_config_versions_serialized_as_decimal_strings():
    value = payload()
    value["configVersion"] = "7"
    client = CapabilityBundleClient(lambda device_id: async_value(value))

    bundle = await client.fetch("device-1")

    assert bundle.config_version == 7


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: value["tools"]["get_weather"].update(type="SHELL"),
        lambda value: value["skills"][0].pop("version"),
        lambda value: value["skills"][0]["triggers"].append(
            {"type": "REGEX", "value": "(", "priority": 1, "caseSensitive": False, "enabled": True}
        ),
        lambda value: value["skills"][0]["toolNames"].append("not-in-tool-map"),
        lambda value: value.update(unexpected="field"),
    ],
)
async def test_rejects_unknown_or_inconsistent_bundle_content(mutate):
    value = copy.deepcopy(payload())
    mutate(value)
    client = CapabilityBundleClient(lambda device_id: async_value(value))

    with pytest.raises(CapabilityBundleError):
        await client.fetch("device-1")


@pytest.mark.asyncio
async def test_parse_errors_do_not_echo_secret_like_response_fields():
    value = payload()
    value["api_key"] = "super-secret-value"
    client = CapabilityBundleClient(lambda device_id: async_value(value))

    with pytest.raises(CapabilityBundleError) as error:
        await client.fetch("device-1")

    message = str(error.value).lower()
    assert "super-secret-value" not in message
    assert "api_key" not in message


async def async_value(value):
    return value
