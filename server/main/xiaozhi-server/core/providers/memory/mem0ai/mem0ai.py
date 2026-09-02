import json
import asyncio
import traceback

from ..base import MemoryProviderBase, logger
from mem0 import MemoryClient
from core.utils.util import check_model_key

TAG = __name__


class MemoryProvider(MemoryProviderBase):
    def __init__(self, config, summary_memory=None):
        super().__init__(config)
        self.api_key = config.get("api_key", "")
        self.api_version = config.get("api_version", "v1.1")
        model_key_msg = check_model_key("Mem0ai", self.api_key)
        if model_key_msg:
            logger.bind(tag=TAG).error(model_key_msg)
            self.use_mem0 = False
            return
        else:
            self.use_mem0 = True

        try:
            self.client = MemoryClient(api_key=self.api_key)
            logger.bind(tag=TAG).info("成功连接到 Mem0ai 服务")
        except Exception as e:
            logger.bind(tag=TAG).error(f"连接到 Mem0ai 服务时发生错误: {str(e)}")
            logger.bind(tag=TAG).error(f"详细错误: {traceback.format_exc()}")
            self.use_mem0 = False

    async def save_memory(self, msgs, session_id=None):
        try:
            if self.use_mem0 and len(msgs) >= 2:
                # Format the content as a message list for mem0
                messages = []
                for message in msgs:
                    role = self._message_field(message, "role")
                    if role == "system":
                        continue

                    if role == "tool":
                        continue

                    content = self._message_field(message, "content")

                    if content is None:
                        continue

                    # Extract content from JSON format if present (for ASR with emotion/language tags)
                    # Same logic as in query_memory method
                    try:
                        if content and content.strip().startswith("{") and content.strip().endswith("}"):
                            data = json.loads(content)
                            if "content" in data:
                                content = data["content"]
                    except (json.JSONDecodeError, KeyError, TypeError):
                        # If parsing fails, use original content
                        pass

                    messages.append({"role": role, "content": content})

                try:
                    result = await asyncio.to_thread(
                        self.client.add, messages, user_id=self.role_id, metadata=self.source_metadata
                    )
                except TypeError:
                    result = await asyncio.to_thread(self.client.add, messages, user_id=self.role_id)
                logger.bind(tag=TAG).debug(f"Save memory result: {result}")
        except Exception as e:
            logger.bind(tag=TAG).error(f"保存记忆失败: {str(e)}")

        return None

    async def query_memory(self, query: str) -> str:
        if not self.use_mem0:
            return ""
        try:
            if not getattr(self, "role_id", None):
                return ""

            filters = {"user_id": self.role_id}

            search_query = query
            try:
                if query.strip().startswith("{") and query.strip().endswith("}"):
                    data = json.loads(query)
                    if "content" in data:
                        search_query = data["content"]
            except (json.JSONDecodeError, KeyError):
                pass

            results = await asyncio.to_thread(
                self.client.search, search_query, filters=filters
            )
            if not results or "results" not in results:
                return ""

            # Format each memory entry with its update time up to minutes
            memories = []
            for entry in results["results"]:
                timestamp = entry.get("updated_at", "")
                if timestamp:
                    try:
                        # Parse and reformat the timestamp
                        dt = timestamp.split(".")[0]  # Remove milliseconds
                        formatted_time = dt.replace("T", " ")
                    except:
                        formatted_time = timestamp
                memory = entry.get("memory", "")
                if timestamp and memory:
                    # Store tuple of (timestamp, formatted_string) for sorting
                    memories.append((timestamp, f"[{formatted_time}] {memory}"))

            # Sort by timestamp in descending order (newest first)
            memories.sort(key=lambda x: x[0], reverse=True)

            # Extract only the formatted strings
            memories_str = "\n".join(f"- {memory[1]}" for memory in memories)
            logger.bind(tag=TAG).debug(f"Query results: {memories_str}")
            return memories_str
        except Exception as e:
            logger.bind(tag=TAG).error(f"查询记忆失败: {str(e)}")
            return ""

    async def clear_memory(self) -> bool:
        if (
            not self.use_mem0
            or getattr(self, "client", None) is None
            or not self.memory_namespace
        ):
            return False
        try:
            await asyncio.to_thread(
                self.client.delete_all,
                user_id=self.memory_namespace,
            )
            return True
        except Exception as e:
            logger.bind(tag=TAG).error(f"清除记忆失败: {str(e)}")
            return False

    async def list_memory_items(self) -> list[dict]:
        if not self.memory_namespace:
            return []
        if not self.use_mem0 or getattr(self, "client", None) is None:
            raise RuntimeError("memory provider unavailable")
        try:
            result = await asyncio.to_thread(
                self.client.get_all,
                user_id=self.memory_namespace,
            )
            entries = result.get("results", []) if isinstance(result, dict) else result or []
            return [
                {
                    "id": str(entry.get("id", "")),
                    "content": str(entry.get("memory") or entry.get("content") or ""),
                    "updated_at": str(entry.get("updated_at") or entry.get("created_at") or ""),
                    "source_device_id": (entry.get("metadata") or {}).get("source_device_id"),
                    "source_profile_id": (entry.get("metadata") or {}).get("source_profile_id"),
                }
                for entry in entries
                if entry.get("id") and (entry.get("memory") or entry.get("content"))
            ]
        except Exception as e:
            logger.bind(tag=TAG).error("列出记忆失败")
            raise RuntimeError("memory provider list failed") from e

    async def update_memory_item(self, memory_id: str, content: str) -> bool:
        if not self.use_mem0 or getattr(self, "client", None) is None:
            return False
        try:
            if not await self._owns_memory_item(memory_id):
                return False
            result = await asyncio.to_thread(self.client.update, memory_id, content)
            return result is not None and result is not False
        except Exception as e:
            logger.bind(tag=TAG).error("更新记忆失败")
            return False

    async def add_memory_item(self, content: str, source_metadata=None) -> bool:
        if not self.use_mem0 or getattr(self, "client", None) is None or not self.memory_namespace:
            return False
        try:
            kwargs = {"user_id": self.memory_namespace}
            metadata = source_metadata if source_metadata is not None else self.source_metadata
            if metadata:
                kwargs["metadata"] = metadata
            try:
                result = await asyncio.to_thread(
                    self.client.add, [{"role": "user", "content": content}], **kwargs
                )
            except TypeError:
                kwargs.pop("metadata", None)
                result = await asyncio.to_thread(
                    self.client.add, [{"role": "user", "content": content}], **kwargs
                )
            if isinstance(result, dict):
                return bool(result.get("results") or result.get("id"))
            return bool(result)
        except Exception:
            logger.bind(tag=TAG).error("新增记忆失败")
            return False

    async def delete_memory_item(self, memory_id: str) -> bool:
        if not self.use_mem0 or getattr(self, "client", None) is None:
            return False
        try:
            if not await self._owns_memory_item(memory_id):
                return False
            result = await asyncio.to_thread(self.client.delete, memory_id)
            return result is not None and result is not False
        except Exception as e:
            logger.bind(tag=TAG).error("删除记忆失败")
            return False

    async def _owns_memory_item(self, memory_id: str) -> bool:
        return any(item["id"] == memory_id for item in await self.list_memory_items())
