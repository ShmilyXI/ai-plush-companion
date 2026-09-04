import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from core.providers.asr.base import ASRProviderBase
from core.providers.asr.fun_local import ASRProvider, validate_model_files


class _PlainTextModel:
    def generate(self, **_kwargs):
        return [{"text": "你好，小智"}]


class FunLocalAsrTest(unittest.IsolatedAsyncioTestCase):
    def test_rejects_incomplete_funasr_model_directory(self):
        with TemporaryDirectory() as model_dir:
            Path(model_dir, "config.yaml").write_text("model: SenseVoiceSmall")
            Path(model_dir, "configuration.json").write_text("{}")

            with self.assertRaisesRegex(
                FileNotFoundError, "model.pt"
            ):
                validate_model_files(model_dir)

    def test_accepts_model_directory_with_model_weight(self):
        with TemporaryDirectory() as model_dir:
            Path(model_dir, "model.pt").touch()

            validate_model_files(model_dir)

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
