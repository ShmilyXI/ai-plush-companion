import hmac
import re
import logging

from aiohttp import web

from config.config_loader import get_private_config_from_api
from config.logger import setup_logging
from core.companion.identity import CompanionIdentity, is_profile_memory_namespace


class CompanionMemoryHandler:
    MAX_CONTENT_LENGTH = 4000
    PROFILE_NAMESPACE = re.compile(r"^companion:[1-9][0-9]*:[A-Za-z0-9_-]+$")

    def __init__(self, config, config_loader=get_private_config_from_api, memory_factory=None):
        self.config = config
        self.config_loader = config_loader
        self.memory_factory = memory_factory or self._create_memory
        try:
            self.logger = setup_logging()
        except FileNotFoundError:
            # Unit-test and migration workers may not have a device config;
            # memory protocol handling remains usable with a standard logger.
            self.logger = logging.getLogger(__name__)

    async def handle_get(self, request):
        provider = await self._provider(request, dict(request.query))
        try:
            items = await provider.list_memory_items()
        except Exception as exc:
            raise web.HTTPBadGateway(text="memory provider operation failed") from exc
        return web.json_response({"items": items})

    async def handle_profile(self, request):
        """Operate on the user/profile namespace used by the consumer App.

        Hardware callers continue to use ``handle_get``/``handle_put`` and send
        a MAC address.  This endpoint requires the resolved user and profile
        IDs explicitly so a device identifier can never become the namespace.
        """
        self._authenticate(request)
        body = await self._json_body(request)
        provider, memory_enabled = await self._profile_provider(request, body)
        operation = str(body.get("operation", "list")).strip().lower()
        if operation == "list":
            try:
                items = await provider.list_memory_items()
            except Exception as exc:
                raise web.HTTPBadGateway(text="memory provider operation failed") from exc
            return web.json_response({
                "success": True,
                "memory_enabled": memory_enabled,
                "items": items or [],
            })
        if operation == "update":
            memory_id = self._required_text(body, "memory_id", 256)
            content = self._required_text(body, "content", self.MAX_CONTENT_LENGTH)
            ok = await self._run_operation(provider.update_memory_item(memory_id, content))
        elif operation == "delete":
            memory_id = self._required_text(body, "memory_id", 256)
            ok = await self._run_operation(provider.delete_memory_item(memory_id))
        elif operation == "clear":
            ok = await self._run_operation(provider.clear_memory())
        else:
            raise web.HTTPBadRequest(text="invalid memory operation")
        if not ok:
            raise web.HTTPBadGateway(text="memory provider operation failed")
        return web.json_response({"success": True, "memory_enabled": memory_enabled, "items": []})

    async def handle_put(self, request):
        body = await self._json_body(request)
        memory_id = self._required_text(body, "memory_id", 256)
        content = self._required_text(body, "content", self.MAX_CONTENT_LENGTH)
        provider = await self._provider(request, body)
        if not await self._run_operation(provider.update_memory_item(memory_id, content)):
            raise web.HTTPBadGateway(text="memory provider operation failed")
        return web.json_response(self._operation_response(provider))

    async def handle_delete(self, request):
        body = await self._json_body(request)
        memory_id = None
        if "memory_id" in body:
            memory_id = self._required_text(body, "memory_id", 256)
        provider = await self._provider(request, body)
        if memory_id is not None:
            operation = provider.delete_memory_item(memory_id)
        else:
            operation = provider.clear_memory()
        success = await self._run_operation(operation)
        if not success:
            raise web.HTTPBadGateway(text="memory provider operation failed")
        return web.json_response(self._operation_response(provider))

    async def handle_migration(self, request):
        self._authenticate(request)
        body = await self._json_body(request)
        source_device_id = self._required_text(body, "source_mac_address", 128)
        target_device_id = self._required_text(body, "target_mac_address", 128)
        mode = self._required_text(body, "mode", 32).lower()
        if source_device_id.lower() == target_device_id.lower():
            raise web.HTTPBadRequest(text="source and target must differ")
        if mode not in {"merge", "overwrite"}:
            raise web.HTTPBadRequest(text="mode must be merge or overwrite")

        source = await self._provider_for_device(request, source_device_id)
        target = await self._provider_for_device(request, target_device_id)
        try:
            source_items = await source.list_memory_items()
            target_items = await target.list_memory_items()
            source_by_content = self._unique_content(source_items)
            target_content = {self._content_key(item.get("content")) for item in target_items}
            imported = 0
            skipped = 0
            backup = list(target_items)

            if mode == "overwrite":
                if not await target.clear_memory():
                    return web.json_response({"success": False, "retryable": True,
                                              "source_count": len(source_items),
                                              "target_count": len(target_items),
                                              "imported_count": 0, "skipped_count": 0,
                                              "recovered": True}, status=502)
                target_content = set()

            for item in source_by_content.values():
                key = self._content_key(item.get("content"))
                if mode == "merge" and key in target_content:
                    skipped += 1
                    continue
                metadata = {
                    key: item.get(key) for key in ("source_device_id", "source_profile_id")
                    if item.get(key)
                }
                if not await target.add_memory_item(item.get("content", ""), metadata):
                    recovered = True
                    if mode == "overwrite":
                        recovered = await self._restore(target, backup)
                    return web.json_response({"success": False, "retryable": True,
                                              "source_count": len(source_items),
                                              "target_count": len(target_items),
                                              "imported_count": imported,
                                              "skipped_count": skipped,
                                              "recovered": recovered}, status=502)
                imported += 1
                target_content.add(key)
            return web.json_response({"success": True, "mode": mode,
                                      "source_count": len(source_items),
                                      "target_count": len(target_items),
                                      "imported_count": imported,
                                      "skipped_count": skipped,
                                      "recovered": True})
        except Exception as exc:
            self.logger.error("memory migration failed", exc_info=exc)
            raise web.HTTPBadGateway(text="memory migration failed") from exc

    async def _provider(self, request, request_data):
        self._authenticate(request)
        requested_device_id = str(request_data.get("mac_address", "")).strip()
        if not requested_device_id:
            raise web.HTTPBadRequest(text="mac_address is required")
        return await self._provider_for_device(request, requested_device_id)

    async def _profile_provider(self, request, body):
        if isinstance(body.get("user_id"), bool):
            raise web.HTTPBadRequest(text="invalid user_id")
        try:
            user_id = int(body.get("user_id"))
        except (TypeError, ValueError) as exc:
            raise web.HTTPBadRequest(text="invalid user_id") from exc
        profile_id = body.get("profile_id")
        namespace = body.get("memory_namespace")
        if not isinstance(profile_id, str) or not profile_id.strip() or len(profile_id) > 128:
            raise web.HTTPBadRequest(text="invalid profile_id")
        expected = f"companion:{user_id}:{profile_id.strip()}"
        if not isinstance(namespace, str) or not self.PROFILE_NAMESPACE.fullmatch(namespace) or namespace != expected:
            raise web.HTTPBadRequest(text="memory namespace does not match profile identity")
        # ``memory_enabled`` is supplied by the manager-api snapshot.  It only
        # describes runtime use; management operations remain available when
        # it is false.
        memory_enabled = body.get("memory_enabled", True)
        if not isinstance(memory_enabled, bool):
            raise web.HTTPBadRequest(text="invalid memory_enabled")
        provider = self.memory_factory(
            self.config,
            namespace,
            False,
            source_metadata={
                "source_user_id": user_id,
                "source_profile_id": profile_id.strip(),
                "source": "app",
            },
        )
        if provider is None:
            raise web.HTTPBadGateway(text="memory provider unavailable")
        return provider, memory_enabled

    async def _provider_for_device(self, request, requested_device_id):
        read_config_from_api = self.config.get("read_config_from_api", False)
        if read_config_from_api:
            private_config = await self.config_loader(
                self.config,
                requested_device_id,
                "manager-api",
            )
        else:
            private_config = self.config
        identity = CompanionIdentity.from_config(private_config)
        if identity is None:
            raise web.HTTPConflict(text="invalid companion identity")
        if not read_config_from_api and not hmac.compare_digest(
            requested_device_id.lower(), identity.device_id.lower()
        ):
            raise web.HTTPForbidden(text="device identity does not match")
        return self.memory_factory(
            private_config,
            identity.memory_namespace,
            not read_config_from_api,
            source_metadata={
                "source_user_id": identity.user_id,
                "source_device_id": identity.device_id,
                "source_profile_id": identity.agent_id,
            },
        )

    async def _restore(self, provider, items):
        if not await provider.clear_memory():
            return False
        for item in items:
            if not await provider.add_memory_item(item.get("content", ""), {
                key: item.get(key) for key in ("source_device_id", "source_profile_id")
                if item.get(key)
            }):
                return False
        return True

    @staticmethod
    def _content_key(content):
        return " ".join(str(content or "").split()).casefold()

    @classmethod
    def _unique_content(cls, items):
        unique = {}
        for item in items:
            key = cls._content_key(item.get("content"))
            if key and key not in unique:
                unique[key] = item
        return unique

    def _authenticate(self, request):
        token = request.headers.get("Authorization", "").removeprefix("Bearer ")
        expected = self.config.get("server", {}).get("auth_key") or self.config.get(
            "manager-api", {}
        ).get("secret", "")
        if not token or not expected or not hmac.compare_digest(token, expected):
            raise web.HTTPUnauthorized()

    async def _json_body(self, request):
        try:
            body = await request.json()
        except Exception as exc:
            raise web.HTTPBadRequest(text="invalid JSON body") from exc
        if not isinstance(body, dict):
            raise web.HTTPBadRequest(text="invalid JSON body")
        return body

    @staticmethod
    async def _run_operation(operation):
        try:
            return await operation
        except Exception as exc:
            raise web.HTTPBadGateway(text="memory provider operation failed") from exc

    @staticmethod
    def _required_text(body, key, max_length):
        value = body.get(key)
        if not isinstance(value, str) or not value.strip() or len(value) > max_length:
            raise web.HTTPBadRequest(text=f"invalid {key}")
        return value.strip()

    def _create_memory(self, config, namespace, save_to_file, source_metadata=None):
        from core.utils.modules_initialize import initialize_modules

        canonical_profile = is_profile_memory_namespace(namespace)
        provider_config = config
        if canonical_profile and isinstance(config, dict) and "summaryMemory" in config:
            # Do not seed a profile namespace from the legacy shared Agent
            # summary while constructing the provider.
            provider_config = dict(config)
            provider_config["summaryMemory"] = None
        provider = initialize_modules(
            logger=self.logger,
            config=provider_config,
            init_memory=True,
        )["memory"]
        summary_memory = None if canonical_profile else (
            None if save_to_file else config.get("summaryMemory")
        )
        provider.init_memory(
            namespace,
            llm=None,
            summary_memory=summary_memory,
            save_to_file=save_to_file,
            source_metadata=source_metadata,
        )
        return provider

    @staticmethod
    def _operation_response(provider):
        response = {"success": True}
        get_summary = getattr(provider, "get_management_summary", None)
        summary = get_summary() if get_summary else None
        if isinstance(summary, str):
            response["summary"] = summary
        return response
