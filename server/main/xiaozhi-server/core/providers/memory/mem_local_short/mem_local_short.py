from ..base import MemoryProviderBase, logger
from contextlib import contextmanager
import fcntl
import time
import json
import os
import tempfile
import threading
import yaml
from config.config_loader import get_project_dir
from config.manage_api_client import generate_and_save_chat_summary
import asyncio
from datetime import datetime, timezone
import uuid
from core.utils.util import check_model_key


short_term_memory_prompt = """
# 时空记忆编织者

## 核心使命
构建可生长的动态记忆网络，在有限空间内保留关键信息的同时，智能维护信息演变轨迹
根据对话记录，总结user的重要信息，以便在未来的对话中提供更个性化的服务

## 记忆法则
### 1. 三维度记忆评估（每次更新必执行）
| 维度       | 评估标准                  | 权重分 |
|------------|---------------------------|--------|
| 时效性     | 信息新鲜度（按对话轮次） | 40%    |
| 情感强度   | 含💖标记/重复提及次数     | 35%    |
| 关联密度   | 与其他信息的连接数量      | 25%    |

### 2. 动态更新机制
**名字变更处理示例：**
原始记忆："曾用名": ["张三"], "现用名": "张三丰"
触发条件：当检测到「我叫X」「称呼我Y」等命名信号时
操作流程：
1. 将旧名移入"曾用名"列表
2. 记录命名时间轴："2024-02-15 14:32:启用张三丰"
3. 在记忆立方追加：「从张三到张三丰的身份蜕变」

### 3. 空间优化策略
- **信息压缩术**：用符号体系提升密度
  - ✅"张三丰[北/软工/🐱]"
  - ❌"北京软件工程师，养猫"
- **淘汰预警**：当总字数≥900时触发
  1. 删除权重分<60且3轮未提及的信息
  2. 合并相似条目（保留时间戳最近的）

## 记忆结构
输出格式必须为可解析的json字符串，不需要解释、注释和说明，保存记忆时仅从对话提取信息，不要混入示例内容
```json
{
  "时空档案": {
    "身份图谱": {
      "现用名": "",
      "特征标记": [] 
    },
    "记忆立方": [
      {
        "事件": "入职新公司",
        "时间戳": "2024-03-20",
        "情感值": 0.9,
        "关联项": ["下午茶"],
        "保鲜期": 30 
      }
    ]
  },
  "关系网络": {
    "高频话题": {"职场": 12},
    "暗线联系": [""]
  },
  "待响应": {
    "紧急事项": ["需立即处理的任务"], 
    "潜在关怀": ["可主动提供的帮助"]
  },
  "高光语录": [
    "最打动人心的瞬间，强烈的情感表达，user的原话"
  ]
}
```
"""


def extract_json_data(json_code):
    start = json_code.find("```json")
    # 从start开始找到下一个```结束
    end = json_code.find("```", start + 1)
    # print("start:", start, "end:", end)
    if start == -1 or end == -1:
        try:
            jsonData = json.loads(json_code)
            return json_code
        except Exception as e:
            print("Error:", e)
        return ""
    jsonData = json_code[start + 7 : end]
    return jsonData


TAG = __name__
_memory_file_lock = threading.RLock()


class MemoryProvider(MemoryProviderBase):
    def __init__(self, config, summary_memory):
        super().__init__(config)
        self.short_memory = ""
        self.memory_items = []
        self._snapshot_version = 0
        self.save_to_file = True
        self.memory_path = get_project_dir() + "data/.memory.yaml"
        self.load_memory(summary_memory)

    def init_memory(
        self, memory_namespace, llm, summary_memory=None, save_to_file=True, **kwargs
    ):
        super().init_memory(memory_namespace, llm, **kwargs)
        self.save_to_file = save_to_file
        self.load_memory(summary_memory)

    def load_memory(self, summary_memory):
        self.short_memory = ""
        self.memory_items = []
        self._snapshot_version = 0
        # api获取到总结记忆后直接返回
        if not self.save_to_file:
            self.short_memory = summary_memory or ""
            if self.short_memory:
                self.memory_items = [self._legacy_item(self.short_memory, self.role_id)]
            return

        with self._storage_lock():
            all_memory = self._read_all_memory_locked()
        if self.role_id in all_memory:
            self._load_stored_namespace(all_memory[self.role_id], self.role_id)
        elif summary_memory:
            self.short_memory = summary_memory
            self.memory_items = [self._new_item(summary_memory, self.role_id)]

    def save_memory_to_file(self, expected_version=None):
        if expected_version is None:
            expected_version = self._snapshot_version
        with self._storage_lock():
            all_memory = self._read_all_memory_locked()
            current_version = self._stored_version(all_memory.get(self.role_id))
            if current_version != expected_version:
                stored = all_memory.get(self.role_id)
                if stored is None:
                    self.short_memory = ""
                    self.memory_items = []
                    self._snapshot_version = 0
                else:
                    self._load_stored_namespace(stored, self.role_id)
                return False
            next_version = current_version + 1
            all_memory[self.role_id] = self._namespace_record(
                next_version, self.short_memory, self.memory_items
            )
            self._write_all_memory_locked(all_memory)
            self._snapshot_version = next_version
            return True

    async def save_memory(self, msgs, session_id=None):
        # 打印使用的模型信息
        model_info = getattr(self.llm, "model_name", str(self.llm.__class__.__name__))
        logger.bind(tag=TAG).debug(f"使用记忆保存模型: {model_info}")
        api_key = getattr(self.llm, "api_key", None)
        memory_key_msg = check_model_key("记忆总结专用LLM", api_key)
        if memory_key_msg:
            logger.bind(tag=TAG).error(memory_key_msg)
        if self.llm is None:
            logger.bind(tag=TAG).error("LLM is not set for memory provider")
            return None

        if len(msgs) < 2:
            return None

        msgStr = ""
        for msg in msgs:
            role = self._message_field(msg, "role")
            content = self._message_field(msg, "content")

            # Extract content from JSON format if present (for ASR with emotion/language tags)
            try:
                if content and content.strip().startswith("{") and content.strip().endswith("}"):
                    data = json.loads(content)
                    if "content" in data:
                        content = data["content"]
            except (json.JSONDecodeError, KeyError, TypeError):
                # If parsing fails, use original content
                pass

            if role == "user":
                msgStr += f"User: {content}\n"
            elif role == "assistant":
                msgStr += f"Assistant: {content}\n"
        if self.short_memory and len(self.short_memory) > 0:
            msgStr += "历史记忆：\n"
            msgStr += self.short_memory

        # 当前时间
        time_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        msgStr += f"当前时间：{time_str}"

        if self.save_to_file:
            try:
                memory_version = self._snapshot_version
                result = self.llm.response_no_stream(
                    short_term_memory_prompt,
                    msgStr,
                    max_tokens=2000,
                    temperature=0.2,
                )
                json_str = extract_json_data(result)
                json.loads(json_str)  # 检查json格式是否正确
                self.short_memory = json_str
                if self.memory_items:
                    self.memory_items[0] = {
                        **self.memory_items[0],
                        "content": json_str,
                        "updated_at": self._now(),
                    }
                    self.memory_items = self.memory_items[:1]
                else:
                    self.memory_items = [self._new_item(json_str)]
                if not self.save_memory_to_file(expected_version=memory_version):
                    logger.bind(tag=TAG).info(
                        "event=local_memory_stale_summary_discarded"
                    )
            except Exception as e:
                logger.bind(tag=TAG).error(f"Error in saving memory: {e}")
        else:
            # 当save_to_file为False时，调用Java端的聊天记录总结接口
            summary_id = session_id if session_id else self.role_id
            await generate_and_save_chat_summary(summary_id)
        logger.bind(tag=TAG).info(
            f"Save memory successful - Role: {self.role_id}, Session: {session_id}"
        )

        return self.short_memory

    async def query_memory(self, query: str) -> str:
        return self.short_memory

    async def query_memory_candidates(self, query: str) -> list[dict]:
        if not self.short_memory:
            return []
        return [{
            "id": "local-summary",
            "content": self.short_memory,
            "confidence": 0.7,
            "source": "local-summary",
        }]

    async def clear_memory(self) -> bool:
        with self._storage_lock():
            all_memory = self._read_all_memory_locked()
            next_version = self._stored_version(
                all_memory.get(self.memory_namespace)
            ) + 1
            all_memory[self.memory_namespace] = self._namespace_record(
                next_version, "", []
            )
            self._write_all_memory_locked(all_memory)
            self.short_memory = ""
            self.memory_items = []
            self._snapshot_version = next_version
        return True

    async def replace_memory_items(self, contents) -> bool:
        self.memory_items = [
            self._new_item(item.get("content", "") if isinstance(item, dict) else item)
            for item in contents
            if (item.get("content", "") if isinstance(item, dict) else item)
        ]
        self._sync_summary()
        if self.save_to_file:
            return self.save_memory_to_file()
        return True

    async def list_memory_items(self) -> list[dict]:
        return [dict(item) for item in self.memory_items]

    async def add_memory_item(self, content: str, source_metadata=None) -> bool:
        if not content or not self.memory_namespace:
            return False
        item = self._new_item(content)
        if isinstance(source_metadata, dict):
            item.update({key: value for key, value in source_metadata.items()
                         if key in ("source_device_id", "source_profile_id") and value})
            source = source_metadata.get("source")
            if isinstance(source, str) and source in {"conversation", "proactive"}:
                item["source"] = source
            memory_ids = source_metadata.get("memory_ids")
            if isinstance(memory_ids, (list, tuple)):
                item["memory_ids"] = [str(value)[:128] for value in memory_ids if value][:16]
            proactive_at = source_metadata.get("proactive_at")
            if isinstance(proactive_at, (int, float)) and not isinstance(proactive_at, bool):
                item["proactive_at"] = int(proactive_at)
        if not self.save_to_file:
            self.memory_items.append(item)
            self._sync_summary()
            return True

        # Reload the namespace while holding the file lock. Another connection
        # may have appended an item since this provider instance was created.
        with self._storage_lock():
            all_memory = self._read_all_memory_locked()
            stored = all_memory.get(self.memory_namespace)
            if stored is None:
                summary = self.short_memory
                items = [dict(value) for value in self.memory_items]
            else:
                summary, items = self._coerce_stored_namespace(
                    stored, self.memory_namespace
                )
            items.append(item)
            summary = "\n".join(value["content"] for value in items)
            next_version = self._stored_version(stored) + 1
            all_memory[self.memory_namespace] = self._namespace_record(
                next_version, summary, items
            )
            self._write_all_memory_locked(all_memory)
            self.short_memory = summary
            self.memory_items = items
            self._snapshot_version = next_version
        return True

    async def update_memory_item(self, memory_id: str, content: str) -> bool:
        if not self.save_to_file:
            return self._update_loaded_item(memory_id, content)
        with self._storage_lock():
            all_memory = self._read_all_memory_locked()
            stored = all_memory.get(self.memory_namespace)
            if stored is None:
                return False
            self._load_stored_namespace(stored, self.memory_namespace)
            if not self._update_loaded_item(memory_id, content):
                return False
            next_version = self._snapshot_version + 1
            all_memory[self.memory_namespace] = self._namespace_record(
                next_version, self.short_memory, self.memory_items
            )
            self._write_all_memory_locked(all_memory)
            self._snapshot_version = next_version
            return True

    async def delete_memory_item(self, memory_id: str) -> bool:
        if not self.save_to_file:
            return self._delete_loaded_item(memory_id)
        with self._storage_lock():
            all_memory = self._read_all_memory_locked()
            stored = all_memory.get(self.memory_namespace)
            if stored is None:
                return False
            self._load_stored_namespace(stored, self.memory_namespace)
            if not self._delete_loaded_item(memory_id):
                return False
            next_version = self._snapshot_version + 1
            all_memory[self.memory_namespace] = self._namespace_record(
                next_version, self.short_memory, self.memory_items
            )
            self._write_all_memory_locked(all_memory)
            self._snapshot_version = next_version
            return True

    def get_management_summary(self):
        return self.short_memory

    def _sync_summary(self):
        self.short_memory = "\n".join(item["content"] for item in self.memory_items)

    def _update_loaded_item(self, memory_id, content):
        for index, item in enumerate(self.memory_items):
            if item["id"] == memory_id:
                self.memory_items[index] = {
                    **item,
                    "content": content,
                    "updated_at": self._now(),
                }
                self._sync_summary()
                return True
        return False

    def _delete_loaded_item(self, memory_id):
        remaining = [item for item in self.memory_items if item["id"] != memory_id]
        if len(remaining) == len(self.memory_items):
            return False
        self.memory_items = remaining
        self._sync_summary()
        return True

    def _load_stored_namespace(self, stored, namespace):
        self.short_memory, self.memory_items = self._coerce_stored_namespace(
            stored, namespace
        )
        self._snapshot_version = self._stored_version(stored)

    def _coerce_stored_namespace(self, stored, namespace):
        if isinstance(stored, dict):
            summary = str(stored.get("summary") or "")
            items = self._normalize_items(stored.get("items", []), namespace)
            if not summary and items:
                summary = "\n".join(item["content"] for item in items)
            return summary, items
        if isinstance(stored, list):
            legacy_items = [
                item if isinstance(item, dict) else {"content": str(item)}
                for item in stored
                if item
            ]
            items = self._normalize_items(legacy_items, namespace)
            return "\n".join(item["content"] for item in items), items
        summary = str(stored or "")
        items = [self._legacy_item(summary, namespace)] if summary else []
        return summary, items

    @staticmethod
    def _stored_version(stored):
        if not isinstance(stored, dict):
            return 0
        try:
            return max(0, int(stored.get("version", 0)))
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _namespace_record(version, summary, items):
        return {
            "version": version,
            "summary": summary,
            "items": [dict(item) for item in items],
        }

    def _read_all_memory_locked(self):
        all_memory = {}
        if os.path.exists(self.memory_path):
            with open(self.memory_path, "r", encoding="utf-8") as stream:
                loaded = yaml.safe_load(stream) or {}
                if isinstance(loaded, dict):
                    all_memory = loaded
        return self._migrate_sidecar_locked(all_memory)

    def _migrate_sidecar_locked(self, all_memory):
        version_path = self.memory_path + ".versions.yaml"
        if not os.path.exists(version_path):
            return all_memory
        with open(version_path, "r", encoding="utf-8") as stream:
            versions = yaml.safe_load(stream) or {}
        if isinstance(versions, dict):
            for namespace, raw_version in versions.items():
                try:
                    sidecar_version = max(0, int(raw_version))
                except (TypeError, ValueError):
                    continue
                stored = all_memory.get(namespace)
                version = max(self._stored_version(stored), sidecar_version)
                summary, items = self._coerce_stored_namespace(stored, namespace)
                all_memory[namespace] = self._namespace_record(
                    version, summary, items
                )
        self._write_all_memory_locked(all_memory)
        os.unlink(version_path)
        self._fsync_directory(os.path.dirname(self.memory_path) or ".")
        return all_memory

    @contextmanager
    def _storage_lock(self):
        directory = os.path.dirname(self.memory_path) or "."
        os.makedirs(directory, exist_ok=True)
        lock_path = self.memory_path + ".lock"
        with _memory_file_lock:
            with open(lock_path, "a+", encoding="utf-8") as lock_file:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

    def _write_all_memory_locked(self, all_memory):
        self._write_yaml_atomic_locked(self.memory_path, all_memory)

    def _write_yaml_atomic_locked(self, target_path, data):
        directory = os.path.dirname(self.memory_path) or "."
        os.makedirs(directory, exist_ok=True)
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=directory,
                prefix=".memory-",
                suffix=".yaml.tmp",
                delete=False,
            ) as stream:
                temp_path = stream.name
                yaml.dump(data, stream, allow_unicode=True)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp_path, target_path)
            temp_path = None
            self._fsync_directory(directory)
        except Exception:
            if temp_path and os.path.exists(temp_path):
                os.unlink(temp_path)
            raise

    @staticmethod
    def _fsync_directory(directory):
        directory_fd = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)

    def _normalize_items(self, items, namespace=None):
        normalized = []
        used_ids = {
            str(item["id"])
            for item in items
            if isinstance(item, dict) and item.get("id")
        }
        for index, item in enumerate(items):
            if not isinstance(item, dict) or not item.get("content"):
                continue
            item_id = item.get("id")
            if item_id:
                item_id = str(item_id)
            elif namespace:
                attempt = 0
                while True:
                    suffix = f":{attempt}" if attempt else ""
                    item_id = str(
                        uuid.uuid5(
                            uuid.NAMESPACE_URL,
                            f"{namespace}:{index}{suffix}",
                        )
                    )
                    if item_id not in used_ids:
                        break
                    attempt += 1
            else:
                item_id = str(uuid.uuid4())
                while item_id in used_ids:
                    item_id = str(uuid.uuid4())
            used_ids.add(item_id)
            normalized_item = {
                "id": item_id,
                "content": str(item["content"]),
                "updated_at": str(item.get("updated_at") or self._now()),
                "source_device_id": item.get("source_device_id"),
                "source_profile_id": item.get("source_profile_id"),
            }
            if item.get("source") in {"conversation", "proactive"}:
                normalized_item["source"] = item["source"]
            if isinstance(item.get("memory_ids"), list):
                normalized_item["memory_ids"] = [str(value)[:128] for value in item["memory_ids"] if value][:16]
            if isinstance(item.get("proactive_at"), (int, float)) and not isinstance(item.get("proactive_at"), bool):
                normalized_item["proactive_at"] = int(item["proactive_at"])
            normalized.append(normalized_item)
        return normalized

    def _new_item(self, content, stable_key=None):
        return {
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, stable_key)) if stable_key else str(uuid.uuid4()),
            "content": str(content),
            "updated_at": self._now(),
            "source_device_id": self.source_metadata.get("source_device_id"),
            "source_profile_id": self.source_metadata.get("source_profile_id"),
        }

    def _legacy_item(self, content, stable_key):
        return {
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, stable_key)),
            "content": str(content),
            "updated_at": self._now(),
            "source_device_id": None,
            "source_profile_id": None,
        }

    @staticmethod
    def _now():
        return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
