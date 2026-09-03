import json
from pathlib import Path

from core.utils import util


ROOT = Path(__file__).resolve().parents[1]


def test_runtime_config_log_summary_excludes_prompts_and_credentials():
    private_config = {
        "prompt": "private role prompt",
        "companion": {"persona_prompt": "private persona"},
        "LLM": {"llm-a": {"api_key": "llm-secret"}},
        "ASR": {"asr-a": {"access_token": "asr-secret"}},
        "selected_module": {
            "LLM": "llm-a",
            "ASR": "asr-a",
            "invalid": {"api_key": "selected-secret"},
        },
        "companion_identity": {"agent_id": "agent-a", "device_id": "device-a"},
        "device_wakeup_words": ["你好紫萱"],
    }

    assert hasattr(util, "runtime_config_log_summary")
    summary = util.runtime_config_log_summary(private_config)
    serialized = json.dumps(summary, ensure_ascii=False)

    assert summary == {
        "source": "manager-api",
        "agent_id": "agent-a",
        "device_id": "device-a",
        "selected_modules": {"LLM": "llm-a", "ASR": "asr-a"},
        "wake_word_count": 1,
    }
    for sensitive in (
        "private role prompt",
        "private persona",
        "llm-secret",
        "asr-secret",
        "selected-secret",
    ):
        assert sensitive not in serialized


def test_connection_log_summary_allows_identity_but_not_auth_headers():
    assert hasattr(util, "connection_log_summary")

    summary = util.connection_log_summary(
        {
            "device-id": "device-a",
            "client-id": "client-a",
            "authorization": "Bearer device-secret",
            "cookie": "session-secret",
        },
        "/xiaozhi/v1/?from=mqtt_gateway",
    )

    assert summary == {
        "device_id": "device-a",
        "client_id": "client-a",
        "transport": "mqtt_gateway",
    }
    assert "secret" not in json.dumps(summary)


def test_doubao_stream_connection_log_never_formats_auth_headers():
    source = (ROOT / "core/providers/asr/doubao_stream.py").read_text(encoding="utf-8")

    assert 'headers: {headers}' not in source
    assert '发送初始化请求: {request_params}' not in source
    assert 'info("正在连接ASR服务")' in source
    assert 'info("发送ASR初始化请求")' in source


def test_prompt_and_ota_logs_emit_metadata_instead_of_runtime_content():
    sources = {
        name: (ROOT / path).read_text(encoding="utf-8")
        for name, path in {
            "connection": "core/connection.py",
            "prompt_manager": "core/utils/prompt_manager.py",
            "ota": "core/api/ota_handler.py",
            "intent": "core/providers/intent/intent_llm/intent_llm.py",
            "alibl": "core/providers/llm/AliBL/AliBL.py",
            "context": "core/utils/context_provider.py",
        }.items()
    }

    forbidden = {
        "connection": "prompt成功 {prompt[:50]}",
        "prompt_manager": "使用快速提示词: {user_prompt[:50]}",
        "ota": "OTA请求头: {request.headers}",
        "intent": "User prompt: {prompt_music}",
        "alibl": "处理后的prompt: {prompt}",
        "context": "已注入动态上下文数据:\\n{self.context_data}",
    }
    for name, value in forbidden.items():
        assert value not in sources[name]

    assert "prompt_length={len(prompt)}" in sources["connection"]
    assert "提示词长度: {len(user_prompt)}" in sources["prompt_manager"]
    assert "OTA请求体长度: {len(data.encode('utf-8'))}" in sources["ota"]
    assert "意图识别提示词长度: {len(prompt_music)}" in sources["intent"]
    assert "处理后的prompt长度: {len(prompt)}" in sources["alibl"]
    assert "构造参数: {dict(call_params" not in sources["alibl"]
    assert "构造参数完成" in sources["alibl"]
    assert "处理后的dialogue: {dialogue}" not in sources["alibl"]
    assert "处理后的dialogue消息数: {len(dialogue)}" in sources["alibl"]
    assert "API {url}" not in sources["context"]
    assert "上下文数据 {url} 失败: {e}" not in sources["context"]
    assert "动态上下文条目数: {len(formatted_lines)}" in sources["context"]
