import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class LcdEmotionLoggingTest(unittest.TestCase):
    def test_successful_emotion_render_is_logged(self):
        source = (ROOT / "main/display/lcd_display.cc").read_text(encoding="utf-8")

        self.assertIn('ESP_LOGI(TAG, "SetEmotion: %s", emotion);', source)


if __name__ == "__main__":
    unittest.main()
