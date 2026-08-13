from __future__ import annotations

import io
import struct
from pathlib import Path
from typing import Mapping, Sequence

MMAP_NAME_LENGTH = 32
MMAP_ENTRY_SIZE = 44


def _fixed_name(value: str, length: int = MMAP_NAME_LENGTH) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > length:
        raise ValueError(f"asset name exceeds {length} bytes: {value}")
    return encoded.ljust(length, b"\x00")


def _checksum(data: bytes) -> int:
    return sum(data) & 0xFFFF


def pack_sr_models(model_dirs: Sequence[Path]) -> bytes:
    models: dict[str, dict[str, bytes]] = {}
    for model_dir in sorted((Path(path) for path in model_dirs), key=lambda path: path.name):
        files = {
            path.name: path.read_bytes()
            for path in sorted(model_dir.iterdir(), key=lambda path: path.name)
            if path.is_file() and path.name != "srmodels.bin"
        }
        if not files:
            raise ValueError(f"model contains no files: {model_dir}")
        models[model_dir.name] = files

    file_count = sum(len(files) for files in models.values())
    header_length = 4 + len(models) * 36 + file_count * 40
    header = io.BytesIO()
    payload = io.BytesIO()
    header.write(struct.pack("<I", len(models)))
    for model_name, files in models.items():
        header.write(_fixed_name(model_name))
        header.write(struct.pack("<I", len(files)))
        for file_name, data in files.items():
            header.write(_fixed_name(file_name))
            header.write(struct.pack("<II", header_length + payload.tell(), len(data)))
            payload.write(data)
    return header.getvalue() + payload.getvalue()


def pack_mmap_assets(files: Mapping[str, bytes], output: Path) -> None:
    ordered = sorted(files.items(), key=lambda item: (Path(item[0]).suffix, Path(item[0]).stem))
    table = io.BytesIO()
    payload = io.BytesIO()
    for name, data in ordered:
        offset = payload.tell()
        payload.write(b"ZZ")
        payload.write(data)
        table.write(_fixed_name(name))
        table.write(struct.pack("<IIHH", len(data), offset, 0, 0))
    combined = table.getvalue() + payload.getvalue()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(struct.pack("<III", len(ordered), _checksum(combined), len(combined)) + combined)


def parse_mmap_assets(data: bytes) -> dict[str, bytes]:
    if len(data) < 12:
        raise ValueError("assets image is shorter than its header")
    file_count, expected_checksum, combined_length = struct.unpack_from("<III", data, 0)
    combined = data[12 : 12 + combined_length]
    if len(combined) != combined_length or _checksum(combined) != expected_checksum:
        raise ValueError("assets image checksum mismatch")
    payload_start = file_count * MMAP_ENTRY_SIZE
    result: dict[str, bytes] = {}
    for index in range(file_count):
        entry_offset = index * MMAP_ENTRY_SIZE
        raw_name, size, offset, _, _ = struct.unpack_from("<32sIIHH", combined, entry_offset)
        name = raw_name.split(b"\x00", 1)[0].decode("utf-8")
        start = payload_start + offset
        if combined[start : start + 2] != b"ZZ":
            raise ValueError(f"asset magic mismatch: {name}")
        result[name] = combined[start + 2 : start + 2 + size]
    return result
