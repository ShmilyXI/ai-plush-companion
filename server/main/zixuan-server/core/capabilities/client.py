from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from .models import CapabilityBundle, CapabilityModelError


class CapabilityBundleError(RuntimeError):
    pass


class CapabilityBundleClient:
    def __init__(self, request: Callable[[str], Awaitable[Any]] | None = None):
        self._request = request

    async def fetch(self, device_id: str) -> CapabilityBundle:
        try:
            raw = await self._fetch_raw(device_id)
            bundle = CapabilityBundle.parse(raw)
            if bundle.device_id != device_id:
                raise CapabilityModelError("device mismatch")
            return bundle
        except CapabilityBundleError:
            raise
        except Exception as exc:
            raise CapabilityBundleError("invalid or unavailable device capability bundle") from exc

    async def _fetch_raw(self, device_id: str):
        if self._request is not None:
            return await self._request(device_id)
        from config.manage_api_client import get_device_capability_bundle

        return await get_device_capability_bundle(device_id)
