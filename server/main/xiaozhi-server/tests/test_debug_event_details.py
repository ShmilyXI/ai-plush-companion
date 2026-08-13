from core.debug_event_details import module_details, safe_endpoint


def test_safe_endpoint_removes_credentials_query_and_fragment():
    assert safe_endpoint("https://user:pass@example.com:8443/v1/chat?token=secret#part") == (
        "https://example.com:8443/v1/chat"
    )
    assert safe_endpoint("wss://example.com/tts") == "wss://example.com/tts"
    assert safe_endpoint("not-a-url") is None
    assert safe_endpoint(None) is None


def test_module_details_exposes_only_debuggable_runtime_metadata():
    config = {
        "selected_module": {"LLM": "LLM_DeepSeek", "TTS": "TTS_Doubao"},
        "LLM": {
            "LLM_DeepSeek": {
                "type": "openai",
                "model_name": "deepseek-chat",
                "base_url": "https://api.deepseek.com/v1?api_key=secret",
                "api_key": "must-not-leak",
            }
        },
        "TTS": {
            "TTS_Doubao": {
                "type": "huoshan_double_stream",
                "speaker": "voice-a",
                "ws_url": "wss://openspeech.example/v3/tts?token=secret",
                "access_token": "must-not-leak",
            }
        },
    }

    assert module_details(config, "LLM") == {
        "module": "LLM_DeepSeek",
        "provider": "openai",
        "model": "deepseek-chat",
        "endpoint": "https://api.deepseek.com/v1",
    }
    assert module_details(config, "TTS") == {
        "module": "TTS_Doubao",
        "provider": "huoshan_double_stream",
        "speaker": "voice-a",
        "endpoint": "wss://openspeech.example/v3/tts",
    }


def test_module_details_marks_missing_or_disabled_modules_without_secrets():
    config = {
        "selected_module": {"Memory": "Memory_nomem"},
        "Memory": {"Memory_nomem": {"type": "nomem"}},
    }

    assert module_details(config, "Memory") == {
        "module": "Memory_nomem",
        "provider": "nomem",
    }
    assert module_details(config, "ASR") == {}
