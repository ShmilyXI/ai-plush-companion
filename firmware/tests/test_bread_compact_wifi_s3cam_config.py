import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BOARD = ROOT / "main" / "boards" / "bread-compact-wifi-s3cam"


def test_bread_camera_board_has_persistent_build_configuration():
    config = json.loads((BOARD / "config.json").read_text(encoding="utf-8"))
    assert config["target"] == "esp32s3"
    assert [build["name"] for build in config["builds"]] == ["bread-compact-wifi-s3cam"]
    flags = set(config["builds"][0]["sdkconfig_append"])
    assert "CONFIG_USE_DEVICE_AEC=y" in flags
    assert "CONFIG_LCD_ST7789_240X320=y" in flags
    assert "CONFIG_USE_CUSTOM_WAKE_WORD=y" in flags
    assert "CONFIG_SR_MN_CN_MULTINET7_QUANT=y" in flags
    assert "CONFIG_SR_MN_EN_NONE=y" in flags
    assert "CONFIG_PARTITION_TABLE_CUSTOM_FILENAME=\"partitions/v2/16m.csv\"" in flags


def test_bread_camera_board_source_keeps_camera_and_audio_paths():
    source = (BOARD / "compact_wifi_board_s3cam.cc").read_text(encoding="utf-8")
    header = (BOARD / "config.h").read_text(encoding="utf-8")
    assert "InitializeCamera" in source
    assert '"has_camera":true' in source
    assert "AUDIO_INPUT_SAMPLE_RATE  16000" in header
    assert "AUDIO_OUTPUT_SAMPLE_RATE 24000" in header
