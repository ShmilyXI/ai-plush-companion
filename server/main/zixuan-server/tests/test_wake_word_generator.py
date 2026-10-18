import hashlib
import json
import struct
from pathlib import Path

import pytest

from core.wake_word.generator import WakeWordAssetGenerator, WakeWordRequest
from server.main.shared.wake_word_assets.packer import parse_mmap_assets


def model_dir(tmp_path: Path) -> Path:
    fst = tmp_path / "fst"
    fst.mkdir()
    (fst / "commands_cn.txt").write_bytes(b"commands-cn")
    (fst / "commands_en.txt").write_bytes(b"commands-en")
    path = tmp_path / "mn7_cn"
    path.mkdir()
    (path / "mn7_data").write_bytes(b"model-data")
    (path / "mn7_index").write_bytes(b"model-index")
    (path / "_MODEL_INFO_").write_bytes(b"model-info")
    return path


def test_generator_converts_phrase_pinyin_and_emits_versioned_metadata(tmp_path: Path):
    result = WakeWordAssetGenerator(model_dir(tmp_path)).generate(
        WakeWordRequest(device_id="device-1", word=" 小布小布 ", version=7, chip="esp32s3", slot_size=0x300000)
    )
    files = parse_mmap_assets(result.content)
    index = json.loads(files["index.json"])

    assert index["multinet_model"]["commands"] == [
        {"command": "xiao bu xiao bu", "text": "小布小布", "action": "wake"}
    ]
    assert index["wake_word_bundle"] == {
        "schema": 1, "version": 7, "word": "小布小布", "chip": "esp32s3", "model": "mn7_cn"
    }
    assert json.loads(files["wake_word.json"])["version"] == 7
    srmodels = files["srmodels.bin"]
    assert struct.unpack_from("<I", srmodels, 0)[0] == 2
    assert srmodels[4:36].split(b"\x00", 1)[0] == b"fst"
    assert result.sha256 == hashlib.sha256(result.content).hexdigest()
    assert result.size == len(result.content)


@pytest.mark.parametrize("word", ["小", "一二三四五六七八九", "hello", "小布 hello", "小布\n"])
def test_generator_rejects_unsupported_words(tmp_path: Path, word: str):
    with pytest.raises(ValueError, match="two to eight Chinese characters"):
        WakeWordAssetGenerator(model_dir(tmp_path)).generate(
            WakeWordRequest(device_id="device-1", word=word, version=1, chip="esp32s3", slot_size=0x300000)
        )


def test_generator_rejects_wrong_chip_and_oversized_package(tmp_path: Path):
    generator = WakeWordAssetGenerator(model_dir(tmp_path))
    with pytest.raises(ValueError, match="unsupported chip"):
        generator.generate(WakeWordRequest("device-1", "小布小布", 1, "esp32c3", 0x300000))
    with pytest.raises(ValueError, match="slot size"):
        generator.generate(WakeWordRequest("device-1", "小布小布", 1, "esp32s3", 64))
