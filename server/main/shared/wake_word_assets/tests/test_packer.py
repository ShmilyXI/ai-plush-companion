import hashlib
import json
from pathlib import Path

from server.main.shared.wake_word_assets.packer import (
    parse_mmap_assets,
    pack_mmap_assets,
    pack_sr_models,
)


def test_pack_mmap_assets_is_deterministic_and_round_trips(tmp_path: Path):
    first = tmp_path / "first.bin"
    second = tmp_path / "second.bin"
    files = {
        "index.json": json.dumps({"version": 1}, separators=(",", ":")).encode(),
        "wake_word.json": b'{"word":"\xe5\xb0\x8f\xe5\xb8\x83\xe5\xb0\x8f\xe5\xb8\x83"}',
    }

    pack_mmap_assets(files, first)
    pack_mmap_assets(dict(reversed(list(files.items()))), second)

    assert first.read_bytes() == second.read_bytes()
    assert parse_mmap_assets(first.read_bytes()) == files
    assert hashlib.sha256(first.read_bytes()).hexdigest() == hashlib.sha256(second.read_bytes()).hexdigest()


def test_pack_sr_models_preserves_model_and_file_names(tmp_path: Path):
    model_dir = tmp_path / "mn7_cn"
    model_dir.mkdir()
    (model_dir / "mn7_data").write_bytes(b"data")
    (model_dir / "mn7_index").write_bytes(b"index")

    packed = pack_sr_models([model_dir])

    assert b"mn7_cn\x00" in packed
    assert b"mn7_data\x00" in packed
    assert b"mn7_index\x00" in packed
