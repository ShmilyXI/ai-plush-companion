import sys
import types
import unittest

from loguru import logger

from core.companion.emotion_policy import CompanionExpression


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module


EXPRESSION = CompanionExpression(
    display_emotion="sad",
    tts_emotion="sad",
    emotion_scale=3,
    speech_rate=-12,
    pitch=-2,
)


class TtsExpressionTest(unittest.TestCase):
    def test_huoshan_merges_expression_without_mutating_defaults(self):
        from core.providers.tts.huoshan_double_stream import build_audio_params
        defaults = {"speech_rate": 0, "loudness_rate": 0}
        result = build_audio_params(defaults, EXPRESSION)
        self.assertEqual(0, defaults["speech_rate"])
        self.assertEqual(-12, result["speech_rate"])
        self.assertEqual("sad", result["emotion"])
        self.assertEqual(3, result["emotion_scale"])

    def test_minimax_merges_expression_without_mutating_defaults(self):
        from core.providers.tts.minimax_httpstream import build_voice_setting
        defaults = {"voice_id": "voice", "speed": 1, "pitch": 0}
        result = build_voice_setting(defaults, EXPRESSION)
        self.assertEqual(1, defaults["speed"])
        self.assertEqual(0.88, result["speed"])
        self.assertEqual(-2, result["pitch"])
        self.assertEqual("sad", result["emotion"])


if __name__ == "__main__":
    unittest.main()
