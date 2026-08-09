import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class LvglSnapshotEncodingContractTest(unittest.TestCase):
    def test_swapped_rgb565_snapshot_uses_big_endian_encoder_format(self):
        source = (ROOT / "main/display/lvgl_display/lvgl_display.cc").read_text(
            encoding="utf-8"
        )
        snapshot_method = re.search(
            r"bool LvglDisplay::SnapshotToJpeg\(.*?\n}\n",
            source,
            re.DOTALL,
        )

        self.assertIsNotNone(snapshot_method)
        method_source = snapshot_method.group(0)
        self.assertIn("__builtin_bswap16", method_source)
        self.assertRegex(
            method_source,
            r"image_to_jpeg_cb\([^;]*V4L2_PIX_FMT_RGB565X",
        )


if __name__ == "__main__":
    unittest.main()
