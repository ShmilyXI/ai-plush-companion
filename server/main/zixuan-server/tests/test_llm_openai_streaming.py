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


def usage_stream(*contents, prompt_tokens=120, completion_tokens=30):
    """A stream whose last chunk has no choices and carries the token usage, as providers send it."""
    stream = MagicMock()
    chunks = [
        SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=content, tool_calls=None))])
        for content in contents
    ]
    chunks.append(SimpleNamespace(
        choices=[], usage=SimpleNamespace(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)))
    stream.__iter__.return_value = iter(chunks)
    return stream


def test_streaming_asks_for_usage_and_records_what_the_provider_reports():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        create = openai_client.return_value.chat.completions.create
        create.return_value = usage_stream("你好", "呀")
        provider = build_provider(module, openai_client)

        assert list(provider.response("session", dialogue())) == ["你好", "呀"]

        assert create.call_args.kwargs["stream_options"] == {"include_usage": True}
        assert provider.last_usage == {"prompt_tokens": 120, "completion_tokens": 30}


def test_usage_is_cleared_when_the_next_reply_reports_none():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        create = openai_client.return_value.chat.completions.create
        create.side_effect = [usage_stream("你好"), stream_chunks(("再见", None))]
        provider = build_provider(module, openai_client)

        list(provider.response("session", dialogue()))
        assert provider.last_usage is not None
        list(provider.response("session", dialogue()))
        assert provider.last_usage is None


def test_a_provider_that_rejects_stream_options_still_answers_and_is_not_asked_again():
    module = load_provider_module()

    class Rejected(Exception):
        status_code = 400

    with patch.object(module.openai, "OpenAI") as openai_client:
        create = openai_client.return_value.chat.completions.create
        create.side_effect = [Rejected("unknown parameter stream_options"),
                              stream_chunks(("你好", None)), stream_chunks(("第二次", None))]
        provider = build_provider(module, openai_client, base_url="https://strict.example/v1")

        assert list(provider.response("session", dialogue())) == ["你好"]
        assert "stream_options" in create.call_args_list[0].kwargs
        assert "stream_options" not in create.call_args_list[1].kwargs

        assert list(provider.response("session", dialogue())) == ["第二次"]
        assert create.call_count == 3
        assert "stream_options" not in create.call_args_list[2].kwargs


def test_other_failures_are_not_retried_without_usage():
    module = load_provider_module()

    class Unavailable(Exception):
        status_code = 503

    with patch.object(module.openai, "OpenAI") as openai_client:
        create = openai_client.return_value.chat.completions.create
        create.side_effect = Unavailable("upstream down")
        provider = build_provider(module, openai_client, base_url="https://flaky.example/v1")

        try:
            list(provider.response("session", dialogue()))
            raise AssertionError("expected the provider error")
        except Unavailable:
            pass
        assert create.call_count == 1
