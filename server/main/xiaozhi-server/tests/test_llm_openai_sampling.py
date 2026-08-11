import importlib.util
import sys
import types
from pathlib import Path
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
    spec = importlib.util.spec_from_file_location("llm_openai_sampling_under_test", module_path)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, {
        "httpx": fake_httpx,
        "openai": fake_openai,
        "openai.types": fake_openai_types,
        "config.logger": fake_logger,
        "core.utils.util": fake_util,
        "core.providers.llm.base": fake_base,
    }):
        spec.loader.exec_module(module)
    return module


def build_provider(module, openai_client, **config):
    stream = MagicMock()
    stream.__iter__.return_value = iter([])
    openai_client.return_value.chat.completions.create.return_value = stream
    return module.LLMProvider({
        "model_name": "test-model",
        "base_url": "https://api.example/v1",
        "api_key": "test-key",
        **config,
    })


def consume(provider):
    list(provider.response("session", [{"role": "user", "content": "你好"}]))


def test_explicit_top_k_is_sent_through_extra_body():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        provider = build_provider(module, openai_client, top_k=40)

        consume(provider)

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert request["extra_body"] == {"top_k": 40}


def test_omitted_top_k_does_not_add_extra_body():
    module = load_provider_module()
    with patch.object(module.openai, "OpenAI") as openai_client:
        provider = build_provider(module, openai_client)

        consume(provider)

        request = openai_client.return_value.chat.completions.create.call_args.kwargs
        assert "extra_body" not in request
