from __future__ import annotations

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
DEDUPE_TIMESTAMP_TOLERANCE_SECONDS = 30


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

    async def query_memory(self, query: str) -> str:
        return ""

    def get_diagnostics(self):
        return {
            **self._diagnostics,
            "layer_hits": dict(self._diagnostics.get("layer_hits") or {}),
        }

    async def _add_conversation(self, session_id: str, outgoing: list[dict]):
        return await self.client.conversation_add(
            self.isolation,
            session_id,
            outgoing,
            task_id=self.task_id,
        )

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
