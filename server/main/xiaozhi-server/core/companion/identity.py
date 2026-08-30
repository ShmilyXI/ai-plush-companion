from dataclasses import dataclass
import re
from typing import Optional


_NAMESPACE = re.compile(r"^companion:[0-9a-f]{64}$")
_PROFILE_NAMESPACE = re.compile(r"^companion:[1-9][0-9]*:[A-Za-z0-9_-]+$")


@dataclass(frozen=True)
class CompanionIdentity:
    user_id: int
    agent_id: str
    device_id: str
    memory_namespace: str

    @classmethod
    def from_config(cls, config: dict) -> Optional["CompanionIdentity"]:
        if not isinstance(config, dict):
            return None
        raw = config.get("companion_identity") or {}
        if not isinstance(raw, dict):
            return None
        if not isinstance(raw.get("agent_id"), str) or not isinstance(
                raw.get("device_id"), str):
            return None
        if isinstance(raw.get("user_id"), bool):
            return None
        profile_namespace = raw.get("profile_memory_namespace")
        if profile_namespace is None:
            profile_namespace = raw.get("profileMemoryNamespace")
        legacy_namespace = raw.get("memory_namespace")
        if legacy_namespace is None:
            legacy_namespace = raw.get("memoryNamespace")
        has_profile_namespace = profile_namespace is not None
        try:
            identity = cls(
                user_id=int(raw["user_id"]),
                agent_id=str(raw["agent_id"]).strip(),
                device_id=str(raw["device_id"]).strip(),
                memory_namespace=str(
                    profile_namespace
                    if has_profile_namespace
                    else legacy_namespace
                ).strip(),
            )
        except (KeyError, TypeError, ValueError):
            return None
        if identity.user_id < 1 or not identity.agent_id or not identity.device_id:
            return None
        if has_profile_namespace:
            expected = f"companion:{identity.user_id}:{identity.agent_id}"
            if (not isinstance(profile_namespace, str)
                    or identity.memory_namespace != expected
                    or not _PROFILE_NAMESPACE.fullmatch(identity.memory_namespace)):
                return None
        elif not _NAMESPACE.fullmatch(identity.memory_namespace):
            return None
        return identity
