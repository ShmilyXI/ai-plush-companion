from datetime import datetime, timezone

from aiohttp import web


class FakeTencentDbMemoryCore:
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.requests = []
        self._runner = None
        self._site = None
        self._request_index = 0
        self._message_index = 0
        self._atomic_index = 0
        self._messages = []
        self._atomic = []
        self._scenarios = {}
        self._cores = {}
        self.base_url = ""

    async def __aenter__(self):
        app = web.Application()
        routes = {
            "/v3/conversation/add": self._conversation_add,
            "/v3/conversation/query": self._conversation_query,
            "/v3/conversation/delete": self._conversation_delete,
            "/v3/atomic/query": self._atomic_query,
            "/v3/atomic/search": self._atomic_search,
            "/v3/atomic/update": self._atomic_update,
            "/v3/atomic/delete": self._atomic_delete,
            "/v3/scenario/ls": self._scenario_list,
            "/v3/scenario/rm": self._scenario_remove,
            "/v3/core/read": self._core_read,
            "/v3/core/write": self._core_write,
        }
        for path, handler in routes.items():
            app.router.add_post(path, handler)
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, "127.0.0.1", 0)
        await self._site.start()
        port = self._site._server.sockets[0].getsockname()[1]
        self.base_url = f"http://127.0.0.1:{port}"
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        if self._runner is not None:
            await self._runner.cleanup()

    async def _body(self, request):
        if request.headers.get("Authorization") != f"Bearer {self.api_key}":
            raise web.HTTPUnauthorized()
        if request.headers.get("x-tdai-service-id") != "ai-plush-companion":
            raise web.HTTPBadRequest(text="missing service id")
        body = await request.json()
        missing = [key for key in ("team_id", "user_id", "agent_id") if not body.get(key)]
        if missing:
            raise web.HTTPUnprocessableEntity(text="missing isolation")
        captured = dict(body)
        captured["_path"] = request.path
        self.requests.append(captured)
        return body

    def _response(self, data, *, code=0, status=200):
        self._request_index += 1
        return web.json_response({
            "code": code,
            "message": "ok" if code == 0 else "not found",
            "request_id": f"req-{self._request_index}",
            "data": data,
        }, status=status)

    @staticmethod
    def _scope(body):
        return body["team_id"], body["user_id"], body["agent_id"]

    @staticmethod
    def _profile_scope(body):
        return body["team_id"], body["agent_id"]

    @staticmethod
    def _now():
        return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    async def _conversation_add(self, request):
        body = await self._body(request)
        accepted = []
        for message in body.get("messages", []):
            self._message_index += 1
            message_id = f"message-{self._message_index}"
            accepted.append(message_id)
            timestamp = message.get("timestamp") or self._now()
            record = {
                "id": message_id,
                "team_id": body["team_id"],
                "user_id": body["user_id"],
                "agent_id": body["agent_id"],
                "task_id": body.get("task_id"),
                "session_id": body.get("session_id"),
                "role": message.get("role"),
                "content": message.get("content"),
                "timestamp": timestamp,
            }
            for key in ("source", "memory_ids", "proactive_at"):
                if key in message:
                    record[key] = message[key]
            self._messages.append(record)
            if message.get("role") == "user" and message.get("content"):
                self._atomic_index += 1
                self._atomic.append({
                    "id": f"atomic-{self._atomic_index}",
                    "team_id": body["team_id"],
                    "user_id": body["user_id"],
                    "agent_id": body["agent_id"],
                    "task_id": body.get("task_id"),
                    "content": message["content"],
                    "created_at": timestamp,
                    "updated_at": timestamp,
                })
        return self._response({
            "accepted_ids": accepted,
            "accepted_versions": ["v1"] * len(accepted),
            "total_count": len(accepted),
        })

    async def _conversation_query(self, request):
        body = await self._body(request)
        records = self._filter(self._messages, body)
        if body.get("session_id"):
            records = [item for item in records if item["session_id"] == body["session_id"]]
        if body.get("task_id"):
            records = [item for item in records if item["task_id"] == body["task_id"]]
        records.sort(key=lambda item: item["timestamp"], reverse=True)
        return self._page(records, body, "messages")

    async def _conversation_delete(self, request):
        body = await self._body(request)
        owned = {item["id"] for item in self._filter(self._messages, body)}
        requested = set(body.get("message_ids") or [])
        deleting = owned & requested
        self._messages = [item for item in self._messages if item["id"] not in deleting]
        return self._response({"deleted_count": len(deleting)})

    async def _atomic_query(self, request):
        body = await self._body(request)
        records = self._filter(self._atomic, body)
        records.sort(key=lambda item: item["updated_at"], reverse=True)
        return self._page(records, body, "items")

    async def _atomic_search(self, request):
        body = await self._body(request)
        records = self._filter(self._atomic, body)[: int(body.get("limit", 8))]
        return self._response({
            "items": [{**item, "score": 1.0} for item in records]
        })

    async def _atomic_update(self, request):
        body = await self._body(request)
        for item in self._filter(self._atomic, body):
            if item["id"] == body.get("id"):
                item["content"] = body.get("content", "")
                item["updated_at"] = self._now()
                return self._response({"id": item["id"], "updated_at": item["updated_at"]})
        return self._response({}, code=404)

    async def _atomic_delete(self, request):
        body = await self._body(request)
        owned = {item["id"] for item in self._filter(self._atomic, body)}
        deleting = owned & set(body.get("ids") or [])
        self._atomic = [item for item in self._atomic if item["id"] not in deleting]
        return self._response({"deleted_count": len(deleting)})

    async def _scenario_list(self, request):
        body = await self._body(request)
        entries = list(self._scenarios.get(self._profile_scope(body), []))
        return self._response({"entries": entries, "total": len(entries)})

    async def _scenario_remove(self, request):
        body = await self._body(request)
        scope = self._profile_scope(body)
        paths = set(body.get("paths") or [])
        self._scenarios[scope] = [
            item for item in self._scenarios.get(scope, []) if item.get("path") not in paths
        ]
        return self._response({"removed": sorted(paths)})

    async def _core_read(self, request):
        body = await self._body(request)
        content = self._cores.get(self._profile_scope(body))
        if content is None:
            return self._response({}, code=404)
        return self._response({"content": content})

    async def _core_write(self, request):
        body = await self._body(request)
        self._cores[self._profile_scope(body)] = body.get("content", "")
        return self._response({"version": "v1", "updated_at": self._now()})

    def _filter(self, records, body):
        scope = self._scope(body)
        return [
            item
            for item in records
            if (item["team_id"], item["user_id"], item["agent_id"]) == scope
        ]

    def _page(self, records, body, key):
        offset = int(body.get("offset", 0))
        limit = int(body.get("limit", 100))
        return self._response({key: records[offset:offset + limit], "total": len(records)})
