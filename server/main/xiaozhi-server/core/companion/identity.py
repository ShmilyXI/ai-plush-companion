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
        raw = config.get("companion_identity") or {}
        try:
            identity = cls(
                user_id=int(raw["user_id"]),
                agent_id=str(raw["agent_id"]).strip(),
                device_id=str(raw["device_id"]).strip(),
                memory_namespace=str(
                    raw["profile_memory_namespace"]
                    if raw.get("profile_memory_namespace") is not None
                    else raw["memory_namespace"]
                ).strip(),
            )
        except (KeyError, TypeError, ValueError):
            return None
        if not identity.agent_id or not identity.device_id:
            return None
        if not (_NAMESPACE.fullmatch(identity.memory_namespace)
                or _PROFILE_NAMESPACE.fullmatch(identity.memory_namespace)):
            return None
        return identity
