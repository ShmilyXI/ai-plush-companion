from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
import json
import re
import tempfile
from pathlib import Path

from pypinyin import Style, lazy_pinyin

from server.main.shared.wake_word_assets.packer import pack_mmap_assets, pack_sr_models

CHINESE_WORD = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff]{2,8}$")


@dataclass(frozen=True)
class WakeWordRequest:
    device_id: str
    word: str
    version: int
    chip: str
    slot_size: int


@dataclass(frozen=True)
class WakeWordBuild:
    content: bytes
    sha256: str
    size: int
    word: str
    version: int


class WakeWordAssetGenerator:
    def __init__(self, model_dir: Path):
        self.model_dir = Path(model_dir)

    def generate(self, request: WakeWordRequest) -> WakeWordBuild:
        word = request.word.strip(" \t\r")
        if not CHINESE_WORD.fullmatch(word):
            raise ValueError("wake word must contain two to eight Chinese characters")
        if request.chip != "esp32s3":
            raise ValueError("unsupported chip")
        command = " ".join(lazy_pinyin(word, style=Style.NORMAL, errors="strict"))
        if not re.fullmatch(r"[a-z]+(?: [a-z]+)*", command):
            raise ValueError("wake word pinyin is unsupported")

        metadata = {
            "schema": 1,
            "version": request.version,
            "word": word,
            "chip": request.chip,
            "model": "mn7_cn",
        }
        index = {
            "version": 1,
            "srmodels": "srmodels.bin",
            "wake_word_bundle": metadata,
            "multinet_model": {
                "language": "cn",
                "duration": 3000,
                "threshold": 0.2,
                "commands": [{"command": command, "text": word, "action": "wake"}],
            },
        }
        with tempfile.TemporaryDirectory(prefix="wake-word-") as directory:
            output = Path(directory) / "assets.bin"
            files = {
                "index.json": json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                "wake_word.json": json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                "srmodels.bin": pack_sr_models([self.model_dir]),
            }
            pack_mmap_assets(files, output)
            content = output.read_bytes()
        if len(content) > request.slot_size - 4096:
            raise ValueError("generated package exceeds slot size")
        return WakeWordBuild(content, sha256(content).hexdigest(), len(content), word, request.version)
