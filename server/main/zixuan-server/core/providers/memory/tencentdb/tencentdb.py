from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import datetime, timezone
from typing import Any

import httpx

from ..base import MemoryProviderBase, logger
from .client import (
    SERVICE_ID,
    TencentDbMemoryClient,
    TencentDbMemoryError,
)


TAG = __name__
MAX_MESSAGE_LENGTH = 8192
CAPTURE_BATCH_SIZE = 10
DEDUPE_TIMESTAMP_TOLERANCE_SECONDS = 30
LAYER_TIMEOUT_SECONDS = 3
L1_MAX_ENTRIES = 8
L1_MAX_CHARS = 3000
L2_MAX_ENTRIES = 5
L2_MAX_CHARS = 1500
L3_MAX_CHARS = 2000
FINAL_MAX_CHARS = 6000
RECALL_STRATEGY = "atomic_hybrid+scenario_navigation+core"
MANAGEMENT_PAGE_SIZE = 100
MANAGEMENT_ITEM_CAP = 10000


class MemoryProvider(MemoryProviderBase):
    def __init__(self, config, summary_memory=None):
        super().__init__(config)
        self.service_id = SERVICE_ID
        self.client = TencentDbMemoryClient(
            str(config.get("memory_core_url", "")).strip(),
            str(config.get("memory_core_api_key", "")).strip(),
            timeout=float(config.get("request_timeout_seconds", 4)),
            service_id=self.service_id,
        )
        self.isolation: dict[str, str] = {}
        self.task_id = ""
        self._diagnostics = {
            "request_id": None,
            "layer_hits": {},
            "degraded_reason": None,
        }

    def init_memory(self, memory_namespace, llm, **kwargs):
        super().init_memory(memory_namespace, llm, **kwargs)
        user_id = self._metadata_text("source_user_id")
        profile_id = self._metadata_text("source_profile_id")
        self.task_id = self._metadata_text("source_device_id")
        if user_id and profile_id:
            self.isolation = {
                "team_id": f"{self.service_id}:user:{user_id}",
                "user_id": user_id,
                "agent_id": profile_id,
            }
        else:
            self.isolation = {}

    async def save_memory(self, msgs, session_id=None):
        session_id = str(session_id or "").strip()
        if not session_id or not self._capture_ready():
            return False
        outgoing = self._convert_messages(msgs)
        if not outgoing:
            return False

        for index, batch in enumerate(self._chunks(outgoing, CAPTURE_BATCH_SIZE)):
            fragment_id = self._capture_session_id(session_id, index)
            if not await self._save_capture_fragment(fragment_id, batch):
                return False
        return True

    async def _save_capture_fragment(
        self, session_id: str, outgoing: list[dict]
    ) -> bool:
        try:
            result = await self._add_conversation(session_id, outgoing)
            self._remember_request_id(result)
            return True
        except Exception as exception:
            if not self._is_uncertain_write(exception):
                self._log_capture_failure(exception)
                return False

        if await self._tail_matches(session_id, outgoing):
            return True

        try:
            result = await self._add_conversation(session_id, outgoing)
            self._remember_request_id(result)
            return True
        except Exception as exception:
            self._log_capture_failure(exception)
            return False

    @staticmethod
    def _capture_session_id(session_id: str, index: int) -> str:
        return session_id if index == 0 else f"{session_id}:memory-part:{index + 1}"

    async def query_memory(self, query: str) -> str:
        query = self._message_content(query)
        if not query or not self.isolation:
            return ""

        layer_names = ("L1", "L2", "L3")
        results = await asyncio.gather(
            self._with_layer_timeout(
                self.client.atomic_search(self.isolation, query, limit=L1_MAX_ENTRIES)
            ),
            self._with_layer_timeout(self.client.scenario_list(self.isolation)),
            self._with_layer_timeout(self.client.core_read(self.isolation)),
            return_exceptions=True,
        )

        successful: dict[str, dict] = {}
        degraded = []
        for layer, result in zip(layer_names, results):
            if isinstance(result, BaseException):
                degraded.append(f"{layer}:{type(result).__name__}")
            elif isinstance(result, dict):
                successful[layer] = result
            else:
                successful[layer] = {}

        l1_values = self._l1_values(successful.get("L1", {}))
        l2_values = self._l2_values(successful.get("L2", {}))
        l3_content = self._l3_content(successful.get("L3", {}))
        request_id = next(
            (
                result.get("_request_id")
                for layer in layer_names
                if isinstance((result := successful.get(layer)), dict)
                and isinstance(result.get("_request_id"), str)
                and result.get("_request_id").strip()
            ),
            None,
        )
        self._diagnostics = {
            "request_id": request_id,
            "recall_strategy": RECALL_STRATEGY,
            "layer_hits": {
                "L1": len(l1_values),
                "L2": len(l2_values),
                "L3": 1 if l3_content else 0,
            },
            "degraded_reason": ",".join(degraded) or None,
        }

        sections = []
        if l1_values:
            sections.append("[原子记忆]\n" + "\n".join(f"- {item}" for item in l1_values))
        if l2_values:
            sections.append("[相关场景]\n" + "\n".join(f"- {item}" for item in l2_values))
        if l3_content:
            sections.append(f"[用户画像]\n{l3_content}")
        return "\n\n".join(sections)[:FINAL_MAX_CHARS].rstrip()

    def get_diagnostics(self):
        return {
            **self._diagnostics,
            "layer_hits": dict(self._diagnostics.get("layer_hits") or {}),
        }

    async def list_memory_items(self) -> list[dict]:
        if not self.isolation:
            return []
        try:
            entries = await self._collect_atomic_items()
        except Exception as exception:
            logger.bind(tag=TAG).warning(
                f"TencentDB 记忆列表失败: {type(exception).__name__}"
            )
            raise RuntimeError("memory provider list failed") from exception

        items = []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            memory_id = self._normalized_text(entry.get("id"))
            content = self._normalized_text(entry.get("content"))
            if not memory_id or not content:
                continue
            items.append({
                "id": memory_id,
                "content": content,
                "updated_at": self._normalized_text(
                    entry.get("updated_at") or entry.get("created_at")
                ),
                "source_device_id": entry.get("task_id"),
                "source_profile_id": entry.get("agent_id")
                or self.isolation.get("agent_id"),
            })
        items.sort(key=lambda item: item["updated_at"], reverse=True)
        return items

    async def update_memory_item(self, memory_id: str, content: str) -> bool:
        memory_id = self._normalized_text(memory_id)
        content = self._normalized_text(content)
        if not memory_id or not content or not self.isolation:
            return False
        try:
            if not await self._owns_atomic_item(memory_id):
                return False
            result = await self.client.atomic_update(
                self.isolation, memory_id, content
            )
            return result is not False and result is not None
        except Exception as exception:
            self._log_management_failure("更新", exception)
            return False

    async def delete_memory_item(self, memory_id: str) -> bool:
        memory_id = self._normalized_text(memory_id)
        if not memory_id or not self.isolation:
            return False

        try:
            if not await self._owns_atomic_item(memory_id):
                return False
            result = await self.client.atomic_delete(self.isolation, [memory_id])
            if not isinstance(result, dict):
                return result is not False and result is not None
            deleted_count = result.get("deleted_count")
            return not isinstance(deleted_count, int) or deleted_count > 0
        except Exception as exception:
            self._log_management_failure("删除", exception)
            return False

    async def add_memory_item(self, content: str, source_metadata=None) -> bool:
        content = self._normalized_text(content)
        if not content or not self.isolation:
            return False
        metadata = source_metadata if isinstance(source_metadata, dict) else {}
        task_id = self._normalized_text(metadata.get("source_device_id")) or self.task_id
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()[:24]
        session_id = f"companion-import:{digest}"
        try:
            result = await self._add_conversation(
                session_id,
                [{"role": "user", "content": content}],
                task_id=task_id,
            )
            return isinstance(result, dict) or (result is not False and result is not None)
        except Exception as exception:
            self._log_management_failure("导入", exception)
            return False

    async def clear_memory(self) -> bool:
        if not self.isolation:
            return False
        try:
            l0_messages = await self._collect_pages(
                self.client.conversation_query, "messages"
            )
            await self._delete_l0(l0_messages)

            l1_items = await self._collect_atomic_items()
            await self._delete_l1(l1_items)

            scenarios = await self.client.scenario_list(self.isolation)
            entries = scenarios.get("entries") if isinstance(scenarios, dict) else []
            for entry in entries if isinstance(entries, list) else []:
                path = self._normalized_text(
                    entry.get("path") if isinstance(entry, dict) else None
                )
                if path and not path.endswith("/"):
                    await self.client.scenario_remove(self.isolation, [path])

            return await self._clear_core()
        except Exception as exception:
            self._log_management_failure("清空", exception)
            return False

    async def _add_conversation(self, session_id: str, outgoing: list[dict], task_id=None):
        return await self.client.conversation_add(
            self.isolation,
            session_id,
            outgoing,
            task_id=self.task_id if task_id is None else task_id,
        )

    async def _collect_atomic_items(self) -> list[dict]:
        return await self._collect_pages(self.client.atomic_query, "items")

    async def _collect_pages(self, method, collection_key: str) -> list[dict]:
        collected = []
        offset = 0
        while True:
            result = await method(
                self.isolation,
                limit=MANAGEMENT_PAGE_SIZE,
                offset=offset,
            )
            if not isinstance(result, dict):
                raise RuntimeError("invalid MemoryCore response")
            total = result.get("total")
            if isinstance(total, int) and total > MANAGEMENT_ITEM_CAP:
                raise RuntimeError("memory item cap exceeded")
            page = result.get(collection_key)
            if not isinstance(page, list):
                raise RuntimeError("invalid MemoryCore collection")
            if len(collected) + len(page) > MANAGEMENT_ITEM_CAP:
                raise RuntimeError("memory item cap exceeded")
            collected.extend(item for item in page if isinstance(item, dict))
            offset += len(page)
            if len(page) < MANAGEMENT_PAGE_SIZE:
                break
            if isinstance(total, int) and offset >= total:
                break
            if not page:
                break
        return collected

    async def _owns_atomic_item(self, memory_id: str) -> bool:
        entries = await self._collect_atomic_items()
        return any(
            self._normalized_text(entry.get("id")) == memory_id
            for entry in entries
            if isinstance(entry, dict)
        )

    async def _delete_l0(self, entries: list[dict]) -> None:
        ids = [
            memory_id
            for entry in entries
            if (memory_id := self._normalized_text(entry.get("id")))
        ]
        for chunk in self._chunks(ids, MANAGEMENT_PAGE_SIZE):
            await self.client.conversation_delete(
                self.isolation,
                message_ids=chunk,
            )

    async def _delete_l1(self, entries: list[dict]) -> None:
        ids = [
            memory_id
            for entry in entries
            if (memory_id := self._normalized_text(entry.get("id")))
        ]
        for chunk in self._chunks(ids, MANAGEMENT_PAGE_SIZE):
            await self.client.atomic_delete(self.isolation, chunk)

    async def _clear_core(self) -> bool:
        try:
            core = await self.client.core_read(self.isolation)
        except TencentDbMemoryError as exception:
            if exception.status == 404 or exception.code == 404:
                return True
            raise
        content = self._normalized_text(core.get("content")) if isinstance(core, dict) else ""
        if not content:
            return True
        await self.client.core_write(self.isolation, "")
        return True

    @staticmethod
    def _chunks(values: list[str], size: int):
        for index in range(0, len(values), size):
            yield values[index:index + size]

    async def _tail_matches(self, session_id: str, outgoing: list[dict]) -> bool:
        try:
            result = await self.client.conversation_query(
                self.isolation,
                session_id=session_id,
                task_id=self.task_id,
                limit=len(outgoing),
                offset=0,
            )
            self._remember_request_id(result)
        except Exception as exception:
            logger.bind(tag=TAG).warning(
                f"TencentDB 记忆写入确认失败: {type(exception).__name__}"
            )
            return False

        persisted = result.get("messages") if isinstance(result, dict) else None
        if not isinstance(persisted, list) or len(persisted) != len(outgoing):
            return False
        persisted = sorted(persisted, key=self._message_timestamp)
        for expected, actual in zip(outgoing, persisted):
            if not isinstance(actual, dict):
                return False
            if actual.get("role") != expected["role"]:
                return False
            if self._normalized_text(actual.get("content")) != expected["content"]:
                return False
            expected_time = self._parse_timestamp(expected.get("timestamp"))
            actual_time = self._parse_timestamp(actual.get("timestamp"))
            if expected_time is None or actual_time is None:
                return False
            if abs((actual_time - expected_time).total_seconds()) > DEDUPE_TIMESTAMP_TOLERANCE_SECONDS:
                return False
        return True

    def _convert_messages(self, msgs) -> list[dict]:
        outgoing = []
        for message in msgs or []:
            if getattr(message, "role", None) not in {"user", "assistant"}:
                continue
            if getattr(message, "is_temporary", False):
                continue
            content = self._message_content(getattr(message, "content", None))
            if not content:
                continue
            outgoing.append({
                "role": message.role,
                "content": content[:MAX_MESSAGE_LENGTH],
                "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            })
        return outgoing

    @classmethod
    def _message_content(cls, value: Any) -> str:
        if not isinstance(value, str):
            return ""
        content = value
        stripped = content.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            try:
                decoded = json.loads(stripped)
            except (json.JSONDecodeError, TypeError):
                decoded = None
            if isinstance(decoded, dict) and isinstance(decoded.get("content"), str):
                content = decoded["content"]
        return content.strip()

    def _capture_ready(self) -> bool:
        return bool(self.isolation and self.task_id)

    @staticmethod
    async def _with_layer_timeout(awaitable):
        return await asyncio.wait_for(awaitable, timeout=LAYER_TIMEOUT_SECONDS)

    @classmethod
    def _l1_values(cls, result: dict) -> list[str]:
        items = result.get("items") if isinstance(result, dict) else None
        if not isinstance(items, list):
            return []
        values = []
        seen = set()
        for item in items:
            if not isinstance(item, dict):
                continue
            content = cls._normalized_text(item.get("content"))
            if not content or content in seen:
                continue
            seen.add(content)
            values.append(content)
        return cls._bounded_values(values, L1_MAX_ENTRIES, L1_MAX_CHARS)

    @classmethod
    def _l2_values(cls, result: dict) -> list[str]:
        entries = result.get("entries") if isinstance(result, dict) else None
        if not isinstance(entries, list):
            return []
        values = []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            path = cls._normalized_text(entry.get("path"))
            summary = cls._normalized_text(entry.get("summary"))
            if not path or path.endswith("/") or not summary:
                continue
            label = path[:-3] if path.endswith(".md") else path
            values.append(f"{label}：{summary}")
        return cls._bounded_values(values, L2_MAX_ENTRIES, L2_MAX_CHARS)

    @classmethod
    def _l3_content(cls, result: dict) -> str:
        content = cls._normalized_text(result.get("content")) if isinstance(result, dict) else ""
        return content[:L3_MAX_CHARS]

    @staticmethod
    def _bounded_values(values: list[str], max_items: int, max_chars: int) -> list[str]:
        selected = []
        remaining = max_chars
        for value in values:
            if len(selected) >= max_items or remaining <= 0:
                break
            bounded = value[:remaining]
            if bounded:
                selected.append(bounded)
                remaining -= len(bounded)
        return selected

    def _metadata_text(self, key: str) -> str:
        value = self.source_metadata.get(key)
        return str(value).strip() if value is not None else ""

    @staticmethod
    def _is_uncertain_write(exception: Exception) -> bool:
        if isinstance(exception, TencentDbMemoryError):
            return exception.retryable
        return isinstance(exception, (httpx.TimeoutException, httpx.NetworkError))

    @staticmethod
    def _normalized_text(value: Any) -> str:
        return value.strip() if isinstance(value, str) else ""

    @classmethod
    def _message_timestamp(cls, message: Any) -> datetime:
        if not isinstance(message, dict):
            return datetime.min.replace(tzinfo=timezone.utc)
        return cls._parse_timestamp(message.get("timestamp")) or datetime.min.replace(
            tzinfo=timezone.utc
        )

    @staticmethod
    def _parse_timestamp(value: Any) -> datetime | None:
        if not isinstance(value, str) or not value.strip():
            return None
        try:
            parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        except ValueError:
            return None
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)

    def _remember_request_id(self, result: Any) -> None:
        request_id = result.get("_request_id") if isinstance(result, dict) else None
        if isinstance(request_id, str) and request_id.strip():
            self._diagnostics["request_id"] = request_id.strip()

    @staticmethod
    def _log_capture_failure(exception: Exception) -> None:
        logger.bind(tag=TAG).warning(
            f"TencentDB 记忆保存失败: {type(exception).__name__}"
        )

    @staticmethod
    def _log_management_failure(operation: str, exception: Exception) -> None:
        logger.bind(tag=TAG).warning(
            f"TencentDB 记忆{operation}失败: {type(exception).__name__}"
        )
