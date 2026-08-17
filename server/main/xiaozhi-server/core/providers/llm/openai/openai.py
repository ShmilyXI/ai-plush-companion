import httpx
import openai
import time
from openai.types import CompletionUsage
from config.logger import setup_logging
from core.utils.util import check_model_key
from core.providers.llm.base import LLMProviderBase
from urllib.parse import urlparse

TAG = __name__
logger = setup_logging()

# 需要禁用思考模式的平台域名及其对应参数（默认关闭思考模式）
THINKING_DISABLED_DOMAINS = {
    "aliyuncs.com": {"enable_thinking": False},
    "bigmodel.cn": {"thinking": {"type": "disabled"}},
    "moonshot.cn": {"thinking": {"type": "disabled"}},
    "volces.com": {"thinking": {"type": "disabled"}},
}


class ThinkingTagFilter:
    OPEN_TAG = "<think>"
    CLOSE_TAG = "</think>"

    def __init__(self):
        self._buffer = ""
        self._inside_thinking = False

    def feed(self, content):
        if not content:
            return ""
        self._buffer += content
        visible = []
        while self._buffer:
            tag = self.CLOSE_TAG if self._inside_thinking else self.OPEN_TAG
            tag_index = self._buffer.find(tag)
            if tag_index >= 0:
                if not self._inside_thinking:
                    visible.append(self._buffer[:tag_index])
                self._buffer = self._buffer[tag_index + len(tag):]
                self._inside_thinking = not self._inside_thinking
                continue

            prefix_length = self._tag_prefix_length(self._buffer, tag)
            safe_end = len(self._buffer) - prefix_length
            if not self._inside_thinking and safe_end > 0:
                visible.append(self._buffer[:safe_end])
            self._buffer = self._buffer[safe_end:]
            break
        return "".join(visible)

    @staticmethod
    def _tag_prefix_length(value, tag):
        maximum = min(len(value), len(tag) - 1)
        for length in range(maximum, 0, -1):
            if value.endswith(tag[:length]):
                return length
        return 0


class LLMProvider(LLMProviderBase):
    def __init__(self, config):
        self.model_name = config.get("model_name")
        self.api_key = config.get("api_key")
        if "base_url" in config:
            self.base_url = config.get("base_url")
        else:
            self.base_url = config.get("url")

        self.stream_enabled = self._parse_bool(
            config.get("stream_enabled"), default=True
        )
        self.thinking_enabled = self._parse_optional_bool(
            config.get("thinking_enabled")
        )
        self.first_content_timeout = self._parse_positive_float(
            config.get("first_content_timeout"), default=8.0
        )
        self.tools_enabled = self._parse_bool(
            config.get("tools_enabled"), default=True
        )
        
        timeout_config = config.get("timeout")
        if isinstance(timeout_config, dict):
            # 细粒度超时配置
            custom_timeout = httpx.Timeout(
                pool=timeout_config.get("pool", 2.0),
                connect=timeout_config.get("connect", 3.0),
                write=timeout_config.get("write", 5.0),
                read=timeout_config.get("read", 60.0)
            )
        elif isinstance(timeout_config, (int, float)) and timeout_config > 0:
            # 兼容旧的单一超时配置（整数或浮点数）
            custom_timeout = httpx.Timeout(timeout_config)
        else:
            # 未配置或配置无效，使用默认值
            custom_timeout = httpx.Timeout(300)

        param_defaults = {
            "max_tokens": int,
            "temperature": lambda x: round(float(x), 1),
            "top_p": lambda x: round(float(x), 1),
            "frequency_penalty": lambda x: round(float(x), 1),
        }

        for param, converter in param_defaults.items():
            value = config.get(param)
            try:
                setattr(
                    self,
                    param,
                    converter(value) if value not in (None, "") else None,
                )
            except (ValueError, TypeError):
                setattr(self, param, None)

        top_k = config.get("top_k")
        if isinstance(top_k, bool):
            self.top_k = None
        elif isinstance(top_k, int):
            self.top_k = top_k if top_k > 0 else None
        elif isinstance(top_k, str) and top_k.strip().isdigit():
            parsed_top_k = int(top_k.strip())
            self.top_k = parsed_top_k if parsed_top_k > 0 else None
        else:
            self.top_k = None

        logger.debug(
            f"意图识别参数初始化: {self.temperature}, {self.max_tokens}, {self.top_p}, "
            f"{self.top_k}, {self.frequency_penalty}"
        )

        model_key_msg = check_model_key("LLM", self.api_key)
        if model_key_msg:
            logger.bind(tag=TAG).error(model_key_msg)
        self.client = openai.OpenAI(api_key=self.api_key, base_url=self.base_url, timeout=custom_timeout)

    @staticmethod
    def _parse_bool(value, default):
        if value is None or value == "":
            return default
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in ("true", "1", "yes", "on"):
                return True
            if normalized in ("false", "0", "no", "off"):
                return False
        return default

    @classmethod
    def _parse_optional_bool(cls, value):
        if value is None or value == "":
            return None
        return cls._parse_bool(value, default=None)

    @staticmethod
    def _parse_positive_float(value, default):
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return default
        return parsed if parsed > 0 else default

    @staticmethod
    def normalize_dialogue(dialogue):
        """自动修复 dialogue 中缺失 content 的消息"""
        for msg in dialogue:
            if "role" in msg and "content" not in msg:
                msg["content"] = ""
        return dialogue

    def _apply_thinking_disabled(self, request_params: dict):
        """根据域名自动禁用思考模式"""
        parsed_url = urlparse(self.base_url)
        domain = parsed_url.netloc

        is_deepseek = "deepseek" in domain.lower() or "deepseek" in str(
            self.model_name
        ).lower()
        if is_deepseek:
            thinking_enabled = (
                self.thinking_enabled
                if self.thinking_enabled is not None
                else False
            )
            params = {
                "thinking": {
                    "type": "enabled" if thinking_enabled else "disabled"
                }
            }
            request_params.setdefault("extra_body", {}).update(params)
            logger.bind(tag=TAG).info(
                f"为 DeepSeek 配置思考模式: {params['thinking']['type']}"
            )
            return

        for disabled_domain, params in THINKING_DISABLED_DOMAINS.items():
            if disabled_domain in domain:
                request_params.setdefault("extra_body", {}).update(params)
                logger.bind(tag=TAG).info(f"为域名 {domain} 禁用思考模式，参数: {params}")
                break

    def _apply_top_k(self, request_params: dict):
        if self.top_k is not None:
            request_params.setdefault("extra_body", {})["top_k"] = self.top_k

    @staticmethod
    def _raise_if_first_content_timed_out(started_at, timeout):
        if time.monotonic() - started_at >= timeout:
            raise TimeoutError("大模型未在规定时间内返回可播放正文")

    def response(self, session_id, dialogue, **kwargs):
        dialogue = self.normalize_dialogue(dialogue)

        request_params = {
            "model": self.model_name,
            "messages": dialogue,
            "stream": self.stream_enabled,
        }

        # 添加可选参数,只有当参数不为None时才添加
        optional_params = {
            "max_tokens": kwargs.get("max_tokens", self.max_tokens),
            "temperature": kwargs.get("temperature", self.temperature),
            "top_p": kwargs.get("top_p", self.top_p),
            "frequency_penalty": kwargs.get("frequency_penalty", self.frequency_penalty),
        }

        for key, value in optional_params.items():
            if value is not None:
                request_params[key] = value

        self._apply_top_k(request_params)

        # 禁用思考模式
        self._apply_thinking_disabled(request_params)

        responses = self.client.chat.completions.create(**request_params)

        thinking_filter = ThinkingTagFilter()
        if not self.stream_enabled:
            choices = getattr(responses, "choices", None) or []
            message = getattr(choices[0], "message", None) if choices else None
            content = getattr(message, "content", "") if message else ""
            visible_content = thinking_filter.feed(content)
            if visible_content:
                yield visible_content
            return

        first_content_started_at = time.monotonic()
        first_content_received = False
        try:
            for chunk in responses:
                try:
                    delta = chunk.choices[0].delta if getattr(chunk, "choices", None) else None
                    content = getattr(delta, "content", "") if delta else ""
                except IndexError:
                    content = ""
                if content:
                    visible_content = thinking_filter.feed(content)
                    if visible_content:
                        first_content_received = True
                        yield visible_content
                if not first_content_received:
                    self._raise_if_first_content_timed_out(
                        first_content_started_at, self.first_content_timeout
                    )
        finally:
            responses.close()

    def response_with_functions(self, session_id, dialogue, functions=None, **kwargs):
        dialogue = self.normalize_dialogue(dialogue)

        request_params = {
            "model": self.model_name,
            "messages": dialogue,
            "stream": self.stream_enabled,
            "tools": functions,
        }
        if kwargs.get("tool_choice") is not None:
            request_params["tool_choice"] = kwargs["tool_choice"]

        optional_params = {
            "max_tokens": kwargs.get("max_tokens", self.max_tokens),
            "temperature": kwargs.get("temperature", self.temperature),
            "top_p": kwargs.get("top_p", self.top_p),
            "frequency_penalty": kwargs.get("frequency_penalty", self.frequency_penalty),
        }

        for key, value in optional_params.items():
            if value is not None:
                request_params[key] = value

        self._apply_top_k(request_params)

        # 禁用思考模式
        self._apply_thinking_disabled(request_params)

        stream = self.client.chat.completions.create(**request_params)
        thinking_filter = ThinkingTagFilter()

        if not self.stream_enabled:
            choices = getattr(stream, "choices", None) or []
            message = getattr(choices[0], "message", None) if choices else None
            content = getattr(message, "content", "") if message else ""
            tool_calls = getattr(message, "tool_calls", None) if message else None
            visible_content = thinking_filter.feed(content)
            if visible_content or tool_calls:
                yield visible_content or None, tool_calls
            return

        first_content_started_at = time.monotonic()
        first_content_received = False
        try:
            for chunk in stream:
                if getattr(chunk, "choices", None):
                    delta = chunk.choices[0].delta
                    content = getattr(delta, "content", "")
                    tool_calls = getattr(delta, "tool_calls", None)
                    visible_content = thinking_filter.feed(content)
                    if visible_content or tool_calls:
                        first_content_received = True
                        yield visible_content or None, tool_calls
                    if not first_content_received:
                        self._raise_if_first_content_timed_out(
                            first_content_started_at, self.first_content_timeout
                        )
                elif isinstance(getattr(chunk, "usage", None), CompletionUsage):
                    usage_info = getattr(chunk, "usage", None)
                    logger.bind(tag=TAG).info(
                        f"Token 消耗：输入 {getattr(usage_info, 'prompt_tokens', '未知')}，"
                        f"输出 {getattr(usage_info, 'completion_tokens', '未知')}，"
                        f"共计 {getattr(usage_info, 'total_tokens', '未知')}"
                    )
        finally:
            stream.close()
