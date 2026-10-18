import importlib.util
import sys
import types
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch


def load_provider_module():
    fake_httpx = types.ModuleType("httpx")
    fake_httpx.Timeout = MagicMock

    fake_openai = types.ModuleType("openai")
    fake_openai.OpenAI = MagicMock
    fake_openai_types = types.ModuleType("openai.types")
    fake_openai_types.CompletionUsage = type("CompletionUsage", (), {})

    fake_logger = types.ModuleType("config.logger")
    fake_logger.setup_logging = MagicMock(return_value=MagicMock())

    fake_util = types.ModuleType("core.utils.util")
    fake_util.check_model_key = MagicMock(return_value=None)

    fake_base = types.ModuleType("core.providers.llm.base")
    fake_base.LLMProviderBase = object

    module_path = Path(__file__).parents[1] / "core/providers/llm/openai/openai.py"
    spec = importlib.util.spec_from_file_location(
        "llm_openai_streaming_under_test", module_path
    )
    module = importlib.util.module_from_spec(spec)
    with patch.dict(
        sys.modules,
        {
            "httpx": fake_httpx,
            "openai": fake_openai,
            "openai.types": fake_openai_types,
            "config.logger": fake_logger,
            "core.utils.util": fake_util,
            "core.providers.llm.base": fake_base,
        },
    ):
        spec.loader.exec_module(module)
    return module


def build_provider(module, openai_client, **config):
    return module.LLMProvider(
        {
            "model_name": "deepseek-chat",
            "base_url": "https://api.deepseek.com",
            "api_key": "test-key",
            **config,
        }
    )


def dialogue():
    return [{"role": "user", "content": "你好"}]


def stream_chunks(*items):
    stream = MagicMock()
    stream.__iter__.return_value = iter(
        [
            SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        delta=SimpleNamespace(content=content, tool_calls=tool_calls)
                    )
                ]
            )
            for content, tool_calls in items
        ]
    )
    return stream


def completion(content="", tool_calls=None):
    return SimpleNamespace(
        choices=[
            SimpleNamespace(
                message=SimpleNamespace(content=content, tool_calls=tool_calls)
            )
        ]
    )


def test_deepseek_defaults_to_streaming_with_thinking_disabled():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = stream_chunks(
            ("你好", None)
        )
        provider = build_provider(module, openai_client, max_tokens=256)

        assert list(provider.response("session", dialogue())) == ["你好"]

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert request["stream"] is True
        assert request["max_tokens"] == 256
        assert request["extra_body"]["thinking"] == {"type": "disabled"}
        assert provider.first_content_timeout == 8.0


def test_explicit_deepseek_thinking_setting_is_sent_to_function_request():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = stream_chunks(
            ("", None)
        )
        provider = build_provider(module, openai_client, thinking_enabled=True)

        list(provider.response_with_functions("session", dialogue(), functions=[]))

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert request["extra_body"]["thinking"] == {"type": "enabled"}


def test_function_request_forwards_required_tool_choice():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = stream_chunks(
            ("", None)
        )
        provider = build_provider(module, openai_client)
        choice = {"type": "function", "function": {"name": "get_weather"}}

        list(
            provider.response_with_functions(
                "session", dialogue(), functions=[], tool_choice=choice
            )
        )

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert request["tool_choice"] == choice


def test_non_deepseek_provider_does_not_receive_deepseek_thinking_parameter():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = stream_chunks(
            ("你好", None)
        )
        provider = build_provider(
            module,
            openai_client,
            model_name="other-model",
            base_url="https://api.example/v1",
        )

        list(provider.response("session", dialogue()))

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert "extra_body" not in request


def test_non_stream_response_keeps_generator_contract():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = completion(
            "<think>内部推理</think>最终答案"
        )
        provider = build_provider(module, openai_client, stream_enabled=False)

        result = list(provider.response("session", dialogue()))

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert request["stream"] is False
        assert result == ["最终答案"]


def test_non_stream_function_response_preserves_content_and_tool_calls():
    module = load_provider_module()
    tool_calls = [SimpleNamespace(id="tool-a")]
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = completion(
            "执行中", tool_calls
        )
        provider = build_provider(module, openai_client, stream_enabled=False)

        result = list(
            provider.response_with_functions("session", dialogue(), functions=[])
        )

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert request["stream"] is False
        assert result == [("执行中", tool_calls)]


def test_string_boolean_and_timeout_config_are_parsed():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        provider = build_provider(
            module,
            openai_client,
            stream_enabled="false",
            thinking_enabled="false",
            first_content_timeout="2.5",
        )

        assert provider.stream_enabled is False
        assert provider.thinking_enabled is False
        assert provider.first_content_timeout == 2.5


def test_stream_stops_when_only_hidden_thinking_exceeds_content_timeout():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = stream_chunks(
            ("<think>仍在推理", None),
            ("还没有正文", None),
        )
        provider = build_provider(module, openai_client, first_content_timeout=1)

        with patch.object(module.time, "monotonic", side_effect=[0.0, 0.5, 1.1]):
            try:
                list(provider.response("session", dialogue()))
            except TimeoutError as error:
                assert "正文" in str(error)
            else:
                raise AssertionError("没有正文时应在配置时间后结束流")


def test_function_stream_stops_when_no_content_or_tool_call_arrives():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        openai_client.return_value.chat.completions.create.return_value = stream_chunks(
            ("<think>仍在推理", None),
            ("还没有正文", None),
        )
        provider = build_provider(module, openai_client, first_content_timeout=1)

        with patch.object(module.time, "monotonic", side_effect=[0.0, 0.5, 1.1]):
            try:
                list(
                    provider.response_with_functions(
                        "session", dialogue(), functions=[]
                    )
                )
            except TimeoutError as error:
                assert "正文" in str(error)
            else:
                raise AssertionError("没有正文或工具调用时应结束流")
