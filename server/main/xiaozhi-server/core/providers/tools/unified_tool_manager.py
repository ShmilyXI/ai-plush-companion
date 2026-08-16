"""统一工具管理器"""

import json
import re
import time
from urllib.parse import parse_qsl, urlsplit
from typing import Dict, List, Optional, Any
from config.logger import setup_logging
from plugins_func.register import Action, ActionResponse
from .base import ToolType, ToolDefinition, ToolExecutor


class ToolManager:
    """统一工具管理器，管理所有类型的工具"""

    _SENSITIVE_DEBUG_NAMES = (
        "token",
        "key",
        "secret",
        "authorization",
        "cookie",
        "password",
        "credential",
        "passphrase",
        "session",
    )
    def __init__(self, conn):
        self.conn = conn
        self.logger = setup_logging()
        self.executors: Dict[ToolType, ToolExecutor] = {}
        self._cached_tools: Optional[Dict[str, ToolDefinition]] = None
        self._cached_function_descriptions: Optional[List[Dict[str, Any]]] = None

    def register_executor(self, tool_type: ToolType, executor: ToolExecutor):
        """注册工具执行器"""
        self.executors[tool_type] = executor
        self._invalidate_cache()
        self.logger.debug(f"注册工具执行器: {tool_type.value}")

    def _invalidate_cache(self):
        """使缓存失效"""
        self._cached_tools = None
        self._cached_function_descriptions = None

    def get_all_tools(self) -> Dict[str, ToolDefinition]:
        """获取所有工具定义"""
        if self._cached_tools is not None:
            return self._cached_tools

        all_tools = {}
        for tool_type, executor in self.executors.items():
            try:
                tools = executor.get_tools()
                for name, definition in tools.items():
                    if name in all_tools:
                        self.logger.warning(f"工具名称冲突: {name}")
                    all_tools[name] = definition
            except Exception as e:
                self.logger.error(f"获取{tool_type.value}工具时出错: {e}")

        self._cached_tools = all_tools
        return all_tools

    def get_function_descriptions(
        self, allowed_names=None
    ) -> List[Dict[str, Any]]:
        """获取所有工具的函数描述（OpenAI格式）"""
        if allowed_names is None and self._cached_function_descriptions is not None:
            return self._cached_function_descriptions

        descriptions = []
        tools = self.get_all_tools()
        for name, tool_definition in tools.items():
            if allowed_names is not None and name not in allowed_names:
                continue
            descriptions.append(tool_definition.description)

        if allowed_names is None:
            self._cached_function_descriptions = descriptions
        return descriptions

    def has_tool(self, tool_name: str) -> bool:
        """检查是否存在指定工具"""
        tools = self.get_all_tools()
        return tool_name in tools

    def get_tool_type(self, tool_name: str) -> Optional[ToolType]:
        """获取工具类型"""
        tools = self.get_all_tools()
        tool_def = tools.get(tool_name)
        return tool_def.tool_type if tool_def else None

    async def execute_tool(
        self, tool_name: str, arguments: Dict[str, Any]
    ) -> ActionResponse:
        """执行工具调用"""
        started_at = time.monotonic()
        safe_arguments = self._safe_debug_preview(arguments)
        self.conn.emit_debug_event(
            "model_tool",
            "tool.called",
            "info",
            "工具开始调用",
            details={"name": tool_name, "arguments": safe_arguments},
        )
        try:
            # 查找工具类型
            tool_type = self.get_tool_type(tool_name)
            if not tool_type:
                result = ActionResponse(
                    action=Action.NOTFOUND,
                    response=f"工具 {tool_name} 不存在",
                )
                self._emit_tool_result(tool_name, result, started_at)
                return result

            # 获取对应的执行器
            executor = self.executors.get(tool_type)
            if not executor:
                result = ActionResponse(
                    action=Action.ERROR,
                    response=f"工具类型 {tool_type.value} 的执行器未注册",
                )
                self._emit_tool_result(tool_name, result, started_at)
                return result

            # 执行工具
            self.logger.info(f"执行工具: {tool_name}，参数: {safe_arguments}")
            result = await executor.execute(self.conn, tool_name, arguments)
            self.logger.debug(f"工具执行结果: {self._compact_tool_result(result)}")
            self._emit_tool_result(tool_name, result, started_at)
            return result

        except Exception as e:
            self.logger.error(f"执行工具 {tool_name} 时出错: {type(e).__name__}")
            self.conn.emit_debug_event(
                "model_tool",
                "tool.failed",
                "error",
                "工具调用失败",
                details={"name": tool_name, "errorClass": type(e).__name__},
                duration_ms=max(0, int((time.monotonic() - started_at) * 1000)),
            )
            return ActionResponse(action=Action.ERROR, response="工具调用失败")

    @staticmethod
    def _compact_tool_result(result):
        value = {
            "action": getattr(getattr(result, "action", None), "name", None),
            "result": getattr(result, "result", None),
            "response": getattr(result, "response", None),
        }
        value = ToolManager._safe_debug_preview(value)
        try:
            return json.dumps(value, ensure_ascii=False, default=str)[:1000]
        except Exception:
            return str(value)[:1000]

    @classmethod
    def _safe_debug_preview(cls, value, *, depth=0, _budget=None):
        if _budget is None:
            _budget = [60]
        if _budget[0] <= 0:
            return "[truncated]"
        _budget[0] -= 1
        if isinstance(value, (bytes, bytearray, memoryview)):
            return "[binary omitted]"
        if value is None or isinstance(value, (bool, int, float)):
            return value
        if isinstance(value, str):
            return cls._safe_debug_string(value)
        if depth >= 4:
            return "[truncated]"
        if isinstance(value, dict):
            preview = {}
            for index, (key, item) in enumerate(value.items()):
                if index >= 10:
                    preview["[truncated]"] = "[truncated]"
                    break
                key_text = str(key)[:80]
                if cls._is_forbidden_debug_key(key_text):
                    preview[key_text] = "[redacted]"
                else:
                    preview[key_text] = cls._safe_debug_preview(
                        item, depth=depth + 1, _budget=_budget
                    )
            return preview
        if isinstance(value, (list, tuple, set, frozenset)):
            return [
                cls._safe_debug_preview(
                    item, depth=depth + 1, _budget=_budget
                )
                for item in list(value)[:10]
            ]
        return cls._safe_debug_string(str(value))

    @staticmethod
    def _is_forbidden_debug_key(key):
        normalized = re.sub(r"[^a-z0-9]", "", key.lower())
        forbidden = (
            "thinking",
            "reasoning",
            "systemprompt",
            "messages",
            "header",
            "headers",
            "config",
            "prompt",
            "rawaudio",
            "audio",
            "pcm",
            "wav",
            "opus",
            "file",
            "path",
        )
        return any(part in normalized for part in forbidden) or any(
            part in normalized for part in ToolManager._SENSITIVE_DEBUG_NAMES
        )

    @staticmethod
    def _safe_debug_string(value):
        text = value[:200]
        sensitive_name = r"(?:token|key|secret|authorization|cookie|password|credential|pass[_-]?phrase|session(?:[_-]?id)?)"
        if re.search(
            rf"(?:thinking|reasoning|system[_-]?prompt|messages|headers|config|prompt|raw[_-]?audio|{sensitive_name})\s*[:=]",
            text,
            re.I,
        ):
            return "[redacted]"
        if re.match(r"^(?:[a-zA-Z]:[\\/]|/|\.\.?[\\/]|~[\\/])", text):
            return "[path omitted]"
        if re.search(r"[\\/][^\\/]+\.(?:wav|pcm|opus|mp3|ogg|flac|m4a)(?:$|[?#])", text, re.I):
            return "[path omitted]"
        if re.match(r"^https?://", text, re.I):
            try:
                parsed = urlsplit(text)
                if parsed.username is not None or parsed.password is not None:
                    return "[redacted]"
                query_keys = {key.lower() for key, _ in parse_qsl(parsed.query)}
            except Exception:
                query_keys = set()
            if any(ToolManager._is_forbidden_debug_key(key) for key in query_keys):
                return "[redacted]"
            return text
        if re.search(
            r"(?:^|[\s'\"])(?:[a-zA-Z]:[\\/]|~[\\/]|\.\.?[\\/]|/(?:tmp|users|home)(?:[\\/]))[^\s'\"]+",
            text,
            re.I,
        ):
            return "[path omitted]"
        return text

    def _emit_tool_result(self, tool_name, result, started_at):
        duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
        if result.action == Action.ERROR:
            self.conn.emit_debug_event(
                "model_tool",
                "tool.failed",
                "error",
                "工具调用失败",
                details={"name": tool_name, "errorClass": "ToolExecutionError"},
                duration_ms=duration_ms,
            )
            return
        self.conn.emit_debug_event(
            "model_tool",
            "tool.completed",
            "info",
            "工具调用完成",
            details={
                "name": tool_name,
                "result": self._compact_tool_result(result),
            },
            duration_ms=duration_ms,
        )

    def get_supported_tool_names(self) -> List[str]:
        """获取所有支持的工具名称"""
        tools = self.get_all_tools()
        return list(tools.keys())

    def refresh_tools(self):
        """刷新工具缓存"""
        self._invalidate_cache()
        self.logger.debug("工具缓存已刷新")

    def get_tool_statistics(self) -> Dict[str, int]:
        """获取工具统计信息"""
        stats = {}
        for tool_type, executor in self.executors.items():
            try:
                tools = executor.get_tools()
                stats[tool_type.value] = len(tools)
            except Exception as e:
                self.logger.error(f"获取{tool_type.value}工具统计时出错: {e}")
                stats[tool_type.value] = 0
        return stats
