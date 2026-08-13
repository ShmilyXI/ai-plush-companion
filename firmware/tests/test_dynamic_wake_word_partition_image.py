import hashlib
import struct
from pathlib import Path

import pytest

from firmware.scripts.build_default_assets import (
    DYNAMIC_WAKE_LAYOUT_VERSION,
    WAKE_SLOT_HEADER,
    WAKE_SLOT_HEADER_SIZE,
    WAKE_SLOT_MAGIC,
    WAKE_SLOT_SIZE,
    assemble_dynamic_wake_word_partition,
)


def test_assembles_base_and_default_wake_word_into_slot_a(tmp_path: Path):
    base = tmp_path / "base.bin"
    wake = tmp_path / "wake.bin"
    base.write_bytes(b"BASE")
    wake.write_bytes(b"WAKE")
    partition_size = 0x800000

    image = assemble_dynamic_wake_word_partition(base, wake, partition_size, 7)

    slot_a = partition_size - 2 * WAKE_SLOT_SIZE
    slot_b = partition_size - WAKE_SLOT_SIZE
    assert len(image) == partition_size
    assert image[:4] == b"BASE"
    magic, layout, package_size, version, sha256 = WAKE_SLOT_HEADER.unpack_from(image, slot_a)
    assert magic == WAKE_SLOT_MAGIC
    assert layout == DYNAMIC_WAKE_LAYOUT_VERSION
    assert package_size == 4
    assert version == 7
    assert sha256 == hashlib.sha256(b"WAKE").digest()
    assert image[slot_a + WAKE_SLOT_HEADER_SIZE : slot_a + WAKE_SLOT_HEADER_SIZE + 4] == b"WAKE"
    assert image[slot_b:] == b"\xff" * WAKE_SLOT_SIZE


def test_rejects_images_that_cross_reserved_boundaries(tmp_path: Path):
    partition_size = 0x800000
    base = tmp_path / "base.bin"
    wake = tmp_path / "wake.bin"
    base.write_bytes(b"B" * (partition_size - 2 * WAKE_SLOT_SIZE + 1))
    wake.write_bytes(b"W")
    with pytest.raises(ValueError, match="base image"):
        assemble_dynamic_wake_word_partition(base, wake, partition_size, 1)

    base.write_bytes(b"B")
    wake.write_bytes(b"W" * (WAKE_SLOT_SIZE - WAKE_SLOT_HEADER_SIZE + 1))
    with pytest.raises(ValueError, match="wake package"):
        assemble_dynamic_wake_word_partition(base, wake, partition_size, 1)
