from __future__ import annotations

import asyncio
from dataclasses import dataclass
import time

from .models import CapabilityBundle


@dataclass(frozen=True)
class _Entry:
    bundle: CapabilityBundle
    loaded_at: float


class CapabilityBundleCache:
    def __init__(self, client, *, clock=time.monotonic, refresh_after_seconds=60,
                 max_stale_seconds=300):
        self._client = client
        self._clock = clock
        self._refresh_after = refresh_after_seconds
        self._max_stale = max_stale_seconds
        self._entries: dict[str, _Entry] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def get(self, device_id: str, *, force_refresh: bool = False) -> CapabilityBundle | None:
        entry = self._entries.get(device_id)
        now = self._clock()
        if not force_refresh and entry is not None and now - entry.loaded_at <= self._refresh_after:
            return entry.bundle
        lock = self._locks.setdefault(device_id, asyncio.Lock())
        async with lock:
            entry = self._entries.get(device_id)
            now = self._clock()
            if not force_refresh and entry is not None and now - entry.loaded_at <= self._refresh_after:
                return entry.bundle
            try:
                fetched = await self._client.fetch(device_id)
            except Exception:
                if entry is not None and now - entry.loaded_at <= self._max_stale:
                    return entry.bundle
                self._entries.pop(device_id, None)
                return None
            if entry is not None and fetched.config_version < entry.bundle.config_version:
                if now - entry.loaded_at <= self._max_stale:
                    return entry.bundle
                self._entries.pop(device_id, None)
                return None
            self._entries[device_id] = _Entry(fetched, now)
            return fetched

    def invalidate(self, device_id: str) -> None:
        self._entries.pop(device_id, None)

    def clear(self) -> None:
        self._entries.clear()
