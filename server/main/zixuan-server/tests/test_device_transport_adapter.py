from pathlib import Path


ROOT = Path(__file__).parents[1]


def test_device_websocket_auth_requires_both_device_and_client_identity():
    source = (ROOT / "core/websocket_server.py").read_text(encoding="utf-8")
    assert "Missing device identity headers" in source


def test_device_text_handler_only_uses_runtime_when_explicitly_injected():
    source = (ROOT / "core/handle/textHandler/listenMessageHandler.py").read_text(encoding="utf-8")
    assert "conversation_runtime" in source
    assert "await startToChat(conn, original_text)" in source
