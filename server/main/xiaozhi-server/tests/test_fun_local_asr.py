import unittest

from core.providers.asr.base import ASRProviderBase
from core.providers.asr.fun_local import ASRProvider


class _PlainTextModel:
    def generate(self, **_kwargs):
        return [{"text": "你好，小智"}]


class FunLocalAsrTest(unittest.IsolatedAsyncioTestCase):
    async def test_returns_plain_text_when_funasr_result_has_no_tags(self):
        provider = ASRProvider.__new__(ASRProvider)
        provider.model = _PlainTextModel()
        provider.language = "auto"

        artifacts = ASRProviderBase.AudioArtifacts(
            pcm_frames=[b"\x00\x00"],
            pcm_bytes=b"\x00\x00",
            file_path=None,
            temp_path=None,
        )

        text, file_path = await provider.speech_to_text(
            [b"\x00\x00"], "session", artifacts
        )

        self.assertEqual("你好，小智", text)
        self.assertIsNone(file_path)


if __name__ == "__main__":
    unittest.main()
