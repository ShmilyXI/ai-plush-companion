#!/usr/bin/env python3
"""Convert a PNG/JPEG/WebP image into an ESP-VoCat gfx RGB565 asset."""

import argparse
import hashlib
import json
import shutil
import struct
import subprocess
from pathlib import Path

HEADER = struct.Struct('<BBHHHHH')


def convert(source: Path, output: Path, width: int, height: int) -> dict[str, object]:
    raw = subprocess.check_output([
        shutil.which('ffmpeg') or 'ffmpeg', '-v', 'error', '-i', str(source),
        '-vf', f'scale={width}:{height}:force_original_aspect_ratio=decrease,'
                f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black',
        '-pix_fmt', 'rgb565le', '-f', 'rawvideo', 'pipe:1',
    ])
    expected = width * height * 2
    if len(raw) != expected:
        raise ValueError(f'converted image is {len(raw)} bytes, expected {expected}')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(HEADER.pack(0x19, 0x04, 0, width, height, width * 2, 0) + raw)
    return {
        'file': output.name,
        'width': width,
        'height': height,
        'format': 'RGB565',
        'bytes': output.stat().st_size,
        'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--width', type=int, default=360)
    parser.add_argument('--height', type=int, default=360)
    parser.add_argument('--manifest', type=Path)
    args = parser.parse_args()
    metadata = convert(args.source, args.output, args.width, args.height)
    if args.manifest:
        args.manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    main()
