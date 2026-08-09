import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AudioZeroVolumePersistenceTest(unittest.TestCase):
    def test_zero_output_volume_is_not_replaced_by_default(self):
        source = (ROOT / "main/audio/audio_codec.cc").read_text(encoding="utf-8")

        self.assertIn("if (output_volume_ < 0)", source)
        self.assertNotIn("if (output_volume_ <= 0)", source)

    def test_loaded_output_volume_is_logged_at_startup(self):
        source = (ROOT / "main/audio/audio_codec.cc").read_text(encoding="utf-8")

        self.assertIn(
            'ESP_LOGI(TAG, "Audio codec started with output volume %d", output_volume_);',
            source,
        )


if __name__ == "__main__":
    unittest.main()
