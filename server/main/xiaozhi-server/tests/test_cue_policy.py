import tempfile
import unittest
from pathlib import Path

from core.companion.cue_policy import CuePolicy


class CuePolicyTest(unittest.TestCase):
    def test_existing_configured_file_is_returned(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sigh.wav"
            path.write_bytes(b"RIFF")
            policy = CuePolicy({"sigh": "sigh.wav"}, resource_root=directory)
            self.assertEqual(str(path.resolve()), policy.resolve("sigh"))

    def test_missing_file_is_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = CuePolicy({"sigh": "missing.wav"}, resource_root=directory)
            self.assertIsNone(policy.resolve("sigh"))

    def test_none_is_skipped(self):
        self.assertIsNone(CuePolicy({}).resolve(None))

    def test_non_string_or_unsafe_paths_are_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            for cue_files in (
                {"sigh": 1},
                {"sigh": True},
                {"sigh": ["sigh.wav"]},
                {"sigh": {"path": "sigh.wav"}},
                {"sigh": None},
                {"sigh": ""},
                {"sigh": "\x00sigh.wav"},
                {"sigh": "/etc/hosts"},
                {"sigh": "../secret.wav"},
                [],
            ):
                with self.subTest(cue_files=cue_files):
                    self.assertIsNone(
                        CuePolicy(cue_files, resource_root=directory).resolve("sigh")
                    )

    def test_symlink_cannot_escape_resource_root(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            resource_root = base / "companion"
            resource_root.mkdir()
            secret = base / "secret.wav"
            secret.write_bytes(b"SECRET")
            (resource_root / "sigh.wav").symlink_to(secret)

            policy = CuePolicy(
                {"sigh": "sigh.wav"}, resource_root=resource_root
            )

            self.assertIsNone(policy.resolve("sigh"))

    def test_strong_user_emotion_selects_a_cue_when_model_omits_it(self):
        policy = CuePolicy({})

        self.assertEqual("sigh", policy.select(None, '{"content":"我真的很累。"}'))
        self.assertEqual("breathe", policy.select(None, "我现在特别焦虑，心跳很快。"))
        self.assertEqual("hesitate", policy.select(None, "有件事不知道该不该说。"))
        self.assertEqual("laugh", policy.select(None, "困扰很久的事终于解决了，太开心了。"))

    def test_existing_model_cue_is_not_overridden_by_keyword_fallback(self):
        policy = CuePolicy({})

        self.assertEqual("sigh", policy.select("sigh", "太开心了，终于成功了。"))

    def test_negated_emotion_does_not_trigger_a_fallback_cue(self):
        policy = CuePolicy({})

        self.assertIsNone(policy.select(None, "我不焦虑，也不紧张。"))
        self.assertEqual("laugh", policy.select(None, "终于不用加班了，太开心了。"))

    def test_model_cue_is_kept_when_user_text_has_no_strong_signal(self):
        policy = CuePolicy({})

        self.assertEqual("hesitate", policy.select("hesitate", "我想和你聊聊。"))
        self.assertIsNone(policy.select(None, "我想和你聊聊。"))

    def test_obvious_expression_conflicts_are_rejected(self):
        policy = CuePolicy({})

        self.assertFalse(policy.is_compatible("sigh", "happy"))
        self.assertFalse(policy.is_compatible("laugh", "sad"))
        self.assertTrue(policy.is_compatible("sigh", "neutral"))


if __name__ == "__main__":
    unittest.main()
