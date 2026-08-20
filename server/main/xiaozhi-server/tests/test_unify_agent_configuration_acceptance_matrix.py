"""Executable simulated acceptance matrices for the remaining rollout gates.

The state machine below deliberately stands in for manager-api persistence. It
defines the observable winner/status/audit contract for races and checks that
rejected requests leave state untouched. Live database and broker concurrency is
outside this test's claim.
"""

import asyncio
from copy import deepcopy
from dataclasses import dataclass, field

import pytest

from core.capabilities.cache import CapabilityBundleCache
from core.capabilities.client import CapabilityBundleClient


@dataclass
class SimulatedState:
    active_version: int = 1
    published: tuple[int, ...] = (1,)
    migration_status: str = "PENDING"
    audit: list[tuple[str, str, int]] = field(default_factory=list)


class SimulatedAgentCoordinator:
    def __init__(self):
        self.state = SimulatedState()
        self.lock = asyncio.Lock()

    async def publish(self, actor: str, *, valid: bool = True):
        async with self.lock:
            if not valid:
                return {"status": "REJECTED", "winner": None}
            next_version = max(self.state.published) + 1
            self.state.published = (*self.state.published, next_version)
            self.state.audit.append(("publish", actor, next_version))
            return {"status": "SUCCEEDED", "winner": next_version}

    async def activate(self, actor: str, version: int):
        async with self.lock:
            if version not in self.state.published:
                return {"status": "REJECTED", "winner": None}
            if self.state.active_version == version:
                return {"status": "IDEMPOTENT", "winner": version}
            self.state.active_version = version
            self.state.audit.append(("activate", actor, version))
            return {"status": "SUCCEEDED", "winner": version}

    async def rollback(self, actor: str, version: int):
        return await self.activate(actor, version)

    async def migrate(self, actor: str, *, authorized: bool = True):
        async with self.lock:
            if not authorized:
                return {"status": "REJECTED", "winner": None}
            if self.state.migration_status == "SUCCEEDED":
                return {"status": "IDEMPOTENT", "winner": "migration-1"}
            self.state.migration_status = "SUCCEEDED"
            self.state.audit.append(("migration", actor, 1))
            return {"status": "SUCCEEDED", "winner": "migration-1"}


@pytest.mark.asyncio
async def test_rejected_operations_do_not_mutate_any_persisted_state():
    coordinator = SimulatedAgentCoordinator()
    before = deepcopy(coordinator.state)
    assert (await coordinator.publish("wrong-user", valid=False))["status"] == "REJECTED"
    assert (await coordinator.activate("operator", 99))["status"] == "REJECTED"
    assert (await coordinator.migrate("wrong-user", authorized=False))["status"] == "REJECTED"
    assert coordinator.state == before


@pytest.mark.asyncio
async def test_publish_race_has_serialized_winners_and_audit_records():
    coordinator = SimulatedAgentCoordinator()
    results = await asyncio.gather(*(coordinator.publish(f"operator-{index}") for index in range(8)))
    winners = [result["winner"] for result in results]
    assert sorted(winners) == list(range(2, 10))
    assert coordinator.state.published == tuple(range(1, 10))
    assert [entry[0] for entry in coordinator.state.audit] == ["publish"] * 8


@pytest.mark.asyncio
async def test_activation_rollback_and_memory_migration_races_define_one_outcome():
    coordinator = SimulatedAgentCoordinator()
    await coordinator.publish("seed")
    activation = await asyncio.gather(
        coordinator.activate("a", 2), coordinator.activate("b", 2),
        coordinator.rollback("c", 1), coordinator.rollback("d", 1),
    )
    assert sum(result["status"] == "SUCCEEDED" for result in activation) == 2
    assert sum(result["status"] == "IDEMPOTENT" for result in activation) == 2
    assert coordinator.state.active_version == 1

    migration = await asyncio.gather(*(coordinator.migrate(f"operator-{index}") for index in range(4)))
    assert sum(result["status"] == "SUCCEEDED" for result in migration) == 1
    assert sum(result["status"] == "IDEMPOTENT" for result in migration) == 3
    assert coordinator.state.migration_status == "SUCCEEDED"


@pytest.mark.asyncio
async def test_capability_refresh_race_reuses_one_new_version():
    calls = 0

    async def fetch(device_id):
        nonlocal calls
        calls += 1
        await asyncio.sleep(0)
        return {"deviceId": device_id, "configVersion": 2, "agentId": "agent-a",
                "agentVersionNo": 2, "skills": [], "tools": {}}

    cache = CapabilityBundleCache(CapabilityBundleClient(fetch))
    bundles = await asyncio.gather(*(cache.get("device-a", force_refresh=True) for _ in range(12)))
    assert calls == 1
    assert {bundle.agent_version_no for bundle in bundles} == {2}
