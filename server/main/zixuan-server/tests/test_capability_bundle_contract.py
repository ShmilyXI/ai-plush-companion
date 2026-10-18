import pytest

from core.capabilities.models import CapabilityBundle, CapabilityModelError


def valid_bundle(**overrides):
    value = {
        "deviceId": "device-a",
        "configVersion": 3,
        "agentId": "agent-a",
        "agentVersionNo": 4,
        "profileMemoryNamespace": "companion:7:agent-a",
        "modelRefs": {"LLM": "model-a"},
        "voiceRef": "voice-a",
        "sourcePolicy": {"app": ["PLUGIN"]},
        "skills": [],
        "tools": {},
    }
    value.update(overrides)
    return value


def test_runtime_bundle_fields_are_captured_and_immutable():
    bundle = CapabilityBundle.parse(valid_bundle())

    bundle.validate_for_runtime()
    assert bundle.agent_id == "agent-a"
    assert bundle.agent_version_no == 4
    assert bundle.profile_memory_namespace == "companion:7:agent-a"
    assert bundle.model_refs["LLM"] == "model-a"


def test_runtime_bundle_rejects_missing_owner_or_tool_executor():
    missing_owner = CapabilityBundle.parse(valid_bundle(agentId=None))
    with pytest.raises(CapabilityModelError):
        missing_owner.validate_for_runtime()

    missing_executor = valid_bundle(
        tools={
            "weather": {
                "name": "weather",
                "type": "PLUGIN",
                "refId": "plugin-weather",
                "runtime": {},
            }
        }
    )
    parsed = CapabilityBundle.parse(missing_executor)
    with pytest.raises(CapabilityModelError):
        parsed.validate_for_runtime()
