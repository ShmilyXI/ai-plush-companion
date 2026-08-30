from core.companion.identity import CompanionIdentity


def _identity(**overrides):
    raw = {
        "user_id": 7,
        "agent_id": "profile-a",
        "device_id": "device-a",
        "memory_namespace": "companion:" + "a" * 64,
    }
    raw.update(overrides)
    return {"companion_identity": raw}


def test_canonical_profile_namespace_is_preferred_when_present():
    identity = CompanionIdentity.from_config(_identity(
        profile_memory_namespace="companion:7:profile-a",
    ))

    assert identity is not None
    assert identity.memory_namespace == "companion:7:profile-a"


def test_profile_namespace_must_match_user_and_agent_identity():
    assert CompanionIdentity.from_config(_identity(
        profile_memory_namespace="companion:8:profile-a",
    )) is None
    assert CompanionIdentity.from_config(_identity(
        profile_memory_namespace="companion:7:profile-b",
    )) is None


def test_legacy_device_namespace_is_kept_when_profile_namespace_is_absent():
    identity = CompanionIdentity.from_config(_identity())

    assert identity is not None
    assert identity.memory_namespace == "companion:" + "a" * 64


def test_identity_rejects_non_string_agent_and_device_ids():
    assert CompanionIdentity.from_config(_identity(agent_id=None)) is None
    assert CompanionIdentity.from_config(_identity(device_id=None)) is None


def test_identity_rejects_boolean_user_ids():
    assert CompanionIdentity.from_config(_identity(user_id=True)) is None


def test_identity_accepts_camel_case_profile_namespace_from_runtime_adapters():
    config = _identity()
    raw = config["companion_identity"]
    raw["profileMemoryNamespace"] = "companion:7:profile-a"

    identity = CompanionIdentity.from_config(config)

    assert identity is not None
    assert identity.memory_namespace == "companion:7:profile-a"
