import asyncio

import pytest

from core.capabilities.cache import CapabilityBundleCache
from core.capabilities.models import CapabilityBundle


class Clock:
    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value


class Client:
    def __init__(self, bundles):
        self.bundles = list(bundles)
        self.calls = 0
        self.error = None

    async def fetch(self, device_id):
        self.calls += 1
        await asyncio.sleep(0)
        if self.error:
            raise self.error
        return self.bundles.pop(0)


def bundle(version):
    return CapabilityBundle(device_id="device-1", config_version=version, skills=(), tools={})


@pytest.mark.asyncio
async def test_concurrent_cache_misses_issue_one_request_per_device():
    client = Client([bundle(1)])
    cache = CapabilityBundleCache(client)

    values = await asyncio.gather(*(cache.get("device-1") for _ in range(8)))

    assert client.calls == 1
    assert {value.config_version for value in values} == {1}


@pytest.mark.asyncio
async def test_refresh_replaces_the_cached_config_version():
    client = Client([bundle(1), bundle(2)])
    cache = CapabilityBundleCache(client)

    assert (await cache.get("device-1")).config_version == 1
    assert (await cache.get("device-1", force_refresh=True)).config_version == 2


@pytest.mark.asyncio
async def test_concurrent_forced_refreshes_share_one_winner():
    client = Client([bundle(1), bundle(2), bundle(3)])
    cache = CapabilityBundleCache(client)

    assert (await cache.get("device-1")).config_version == 1
    values = await asyncio.gather(*(cache.get("device-1", force_refresh=True) for _ in range(8)))

    assert client.calls == 2
    assert {value.config_version for value in values} == {2}


@pytest.mark.asyncio
async def test_refresh_failure_uses_a_snapshot_no_older_than_five_minutes():
    clock = Clock()
    client = Client([bundle(1)])
    cache = CapabilityBundleCache(client, clock=clock, max_stale_seconds=300)
    cached = await cache.get("device-1")
    clock.value = 299
    client.error = RuntimeError("manager unavailable")

    assert await cache.get("device-1", force_refresh=True) is cached


@pytest.mark.asyncio
async def test_expired_snapshot_fails_closed_and_invalidation_removes_it():
    clock = Clock()
    client = Client([bundle(1), bundle(2)])
    cache = CapabilityBundleCache(client, clock=clock, max_stale_seconds=300)
    await cache.get("device-1")
    clock.value = 301
    client.error = RuntimeError("manager unavailable")

    assert await cache.get("device-1", force_refresh=True) is None
    client.error = None
    cache.invalidate("device-1")
    assert (await cache.get("device-1")).config_version == 2
    assert client.calls == 3
