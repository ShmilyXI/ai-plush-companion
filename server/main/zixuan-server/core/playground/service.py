from __future__ import annotations

from typing import Any

from .protocol import PlaygroundInput, PlaygroundSnapshot, VirtualDevice
from .session import PlaygroundSession


class PlaygroundService:
    def __init__(self) -> None:
        self._sessions: dict[str, PlaygroundSession] = {}

    def create(self, payload: dict[str, Any]) -> PlaygroundSnapshot:
        device_payload = payload.get("virtual_device") or {}
        device = VirtualDevice(
            width=int(device_payload.get("width", 240)),
            height=int(device_payload.get("height", 240)),
            depth=int(device_payload.get("depth", 8)),
            orientation=str(device_payload.get("orientation", "square")),
            screen=bool(device_payload.get("screen", True)),
            camera=bool(device_payload.get("camera", False)),
            microphone=bool(device_payload.get("microphone", True)),
            activity_sensor=bool(device_payload.get("activity_sensor", False)),
        )
        session_id = str(payload["session_id"])
        config = dict(payload.get("config") or {})
        snapshot = PlaygroundSnapshot(session_id, int(payload.get("snapshot_version", 1)), config, device)
        self._sessions[session_id] = PlaygroundSession(snapshot, dict(payload.get("runtime_models") or {}))
        return snapshot

    def get(self, session_id: str) -> PlaygroundSession:
        try:
            return self._sessions[session_id]
        except KeyError as exc:
            raise KeyError("playground session not found") from exc

    def close(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)

    async def handle_create(self, request: Any) -> Any:
        from aiohttp import web
        payload = await request.json()
        return web.json_response({"session_id": self.create(payload).session_id})

    async def handle_input(self, request: Any) -> Any:
        from aiohttp import web
        session = self.get(request.match_info["session_id"])
        payload = await request.json()
        parsed = PlaygroundInput.parse(payload)
        event = session.accept(payload)
        generated = [event]
        generated.extend(await session.execute(parsed))
        return web.json_response({"events": [item.to_dict() for item in generated]})

    async def handle_events(self, request: Any) -> Any:
        import json
        from aiohttp import web
        session = self.get(request.match_info["session_id"])
        after = int(request.query.get("after", "0"))
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        for event in session.events_after(after):
            await response.write(f"id: {event.sequence}\nevent: playground-event\ndata: {json.dumps(event.to_dict(), ensure_ascii=False)}\n\n".encode())
        await response.write_eof()
        return response

    async def handle_close(self, request: Any) -> Any:
        from aiohttp import web
        self.close(request.match_info["session_id"])
        return web.json_response({"ok": True})
