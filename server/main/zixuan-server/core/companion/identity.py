from dataclasses import dataclass
import re
from typing import Optional


_NAMESPACE = re.compile(r"^companion:[0-9a-f]{64}$")


@dataclass(frozen=True)
class CompanionIdentity:
    user_id: int
    agent_id: str
    device_id: str
    memory_namespace: str
    profile_memory_namespace: str | None = None

    @property
    def effective_memory_namespace(self) -> str:
        return self.profile_memory_namespace or self.memory_namespace

    @classmethod
    def from_config(cls, config: dict) -> Optional["CompanionIdentity"]:
        raw = config.get("companion_identity") or {}
        try:
            identity = cls(
                user_id=int(raw["user_id"]),
                agent_id=str(raw["agent_id"]).strip(),
                device_id=str(raw["device_id"]).strip(),
                memory_namespace=str(raw["memory_namespace"]).strip(),
                profile_memory_namespace=(
                    str(raw["profile_memory_namespace"]).strip()
                    if raw.get("profile_memory_namespace")
                    else None
                ),
            )
        except (KeyError, TypeError, ValueError):
            return None
        if not identity.agent_id or not identity.device_id:
            return None
        if not _NAMESPACE.fullmatch(identity.memory_namespace):
            return None
        if identity.profile_memory_namespace is not None and not canonical_memory_namespace_matches(
            identity.profile_memory_namespace
        ):
            return None
        return identity


def canonical_memory_namespace(user_id: int | str, profile_id: str) -> str:
    user = str(user_id).strip()
    profile = str(profile_id).strip()
    if not user or not profile or ":" in user or ":" in profile:
        raise ValueError("invalid memory owner")
    return f"companion:{user}:{profile}"


def canonical_memory_namespace_matches(value: str) -> bool:
    parts = value.split(":", 2)
    return len(parts) == 3 and parts[0] == "companion" and bool(parts[1]) and bool(parts[2])
