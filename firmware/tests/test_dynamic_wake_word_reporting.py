from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def source(name):
    return (ROOT / "main" / name).read_text(encoding="utf-8")


def test_firmware_reports_wake_word_state_and_heartbeat_reuses_system_info():
    board = source("boards/common/board.cc")
    heartbeat = source("device_heartbeat.cc")
    ota = source("ota.cc")
    mcp = source("mcp_server.cc")

    assert 'R"("wake_word":)"' in board
    assert "WakeWordAssets::GetInstance().GetStatusJson()" in board
    assert "board.GetSystemInfoJson()" in heartbeat
    assert 'GetString("token")' in heartbeat
    assert 'SetHeader("Authorization"' in heartbeat
    assert 'SetHeader("Authorization"' in ota
    assert 'Property("sha256", kPropertyTypeString, std::string())' in mcp
    assert 'Property("size", kPropertyTypeInteger, 0)' in mcp
    assert 'Property("version", kPropertyTypeInteger, 0)' in mcp
    assert 'Property("word", kPropertyTypeString, std::string())' in mcp
    assert "WakeWordAssets::GetInstance().SetPending" in mcp
    assert "partially populated dynamic wake word request" in mcp
