import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class UpstreamLcdSubtitleLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.kconfig = (ROOT / "main/Kconfig.projbuild").read_text(encoding="utf-8")
        cls.header = (ROOT / "main/display/lcd_display.h").read_text(
            encoding="utf-8"
        )
        cls.source = (ROOT / "main/display/lcd_display.cc").read_text(
            encoding="utf-8"
        )

    def assert_whitespace_independent_contains(self, text, fragment, message):
        self.assertIn(
            re.sub(r"\s+", "", fragment),
            re.sub(r"\s+", "", text),
            message,
        )

    def assert_absent(self, text, fragment, message):
        self.assertFalse(fragment in text, f"{message}: {fragment}")

    def extract_primary_branch(self, text, condition):
        match = re.search(
            rf"(?ms)^[ \t]*#if\s+{re.escape(condition)}\s*$\n"
            r"(?P<body>.*?)(?=^[ \t]*#(?:elif|else|endif)\b)",
            text,
        )
        self.assertIsNotNone(match, f"missing primary conditional branch: #if {condition}")
        return match.group("body")

    def extract_function(self, name):
        matches = list(
            re.finditer(rf"void\s+LcdDisplay::{re.escape(name)}\s*\(", self.source)
        )
        self.assertTrue(matches, f"missing function: LcdDisplay::{name}")
        match = matches[-1]
        opening_brace = self.source.find("{", match.end())
        self.assertNotEqual(opening_brace, -1, f"function has no body: {name}")

        depth = 0
        for index in range(opening_brace, len(self.source)):
            if self.source[index] == "{":
                depth += 1
            elif self.source[index] == "}":
                depth -= 1
                if depth == 0:
                    return self.source[match.start() : index + 1]
        self.fail(f"function body is not closed: {name}")

    def test_overlay_configuration_and_state_are_removed(self):
        self.assert_absent(
            self.kconfig,
            "COMPANION_SUBTITLE_OVERLAY",
            "legacy subtitle overlay Kconfig option remains",
        )
        for fragment in (
            "CONFIG_COMPANION_SUBTITLE_OVERLAY",
            "subtitle_gradient_",
            "subtitle_viewport_",
            "subtitle_shadow_label_",
            "subtitle_is_user_",
            "InitializeSubtitleGradient",
        ):
            with self.subTest(fragment=fragment):
                self.assert_absent(
                    self.header,
                    fragment,
                    "legacy subtitle overlay header state remains",
                )

    def test_overlay_only_source_state_and_constants_are_removed(self):
        for fragment in (
            "CONFIG_COMPANION_SUBTITLE_OVERLAY",
            "kSubtitleMaxLines",
            "kSubtitleTopFade",
            "kSubtitleBottomPadding",
            "kSubtitleHorizontalPadding",
            "kEmotionTopOffset",
            "kEmotionMaxSize",
            "kUserSubtitleColor",
            "kAssistantSubtitleColor",
            "InitializeSubtitleGradient",
            "ApplySubtitleRoleColor",
            "ScaleEmotionImage",
            "subtitle_is_user_",
        ):
            with self.subTest(fragment=fragment):
                self.assert_absent(
                    self.source,
                    fragment,
                    "legacy subtitle overlay source state remains",
                )

    def test_default_layout_keeps_centered_emoji_and_multiline_subtitles(self):
        self.assert_whitespace_independent_contains(
            self.source,
            "lv_obj_align(emoji_box_, LV_ALIGN_CENTER, 0, 0);",
            "emoji box must remain centered",
        )

    def test_multiline_subtitle_uses_upstream_auto_height_layout(self):
        multiline_layout = self.extract_primary_branch(
            self.source, "CONFIG_USE_MULTILINE_CHAT_MESSAGE"
        )

        self.assert_whitespace_independent_contains(
            multiline_layout,
            "lv_obj_set_height(bottom_bar_, LV_SIZE_CONTENT);",
            "multiline subtitle bar must use automatic height",
        )
        self.assert_whitespace_independent_contains(
            multiline_layout,
            "lv_label_set_long_mode(chat_message_label_, LV_LABEL_LONG_WRAP);",
            "multiline subtitle label must wrap",
        )
        self.assert_whitespace_independent_contains(
            multiline_layout,
            "lv_obj_set_width(chat_message_label_, LV_HOR_RES - lvgl_theme->spacing(8));",
            "multiline subtitle label must have the themed horizontal width",
        )
        self.assert_whitespace_independent_contains(
            multiline_layout,
            "lv_obj_align(bottom_bar_, LV_ALIGN_BOTTOM_MID, 0, 0);",
            "multiline subtitle bar must stay bottom aligned",
        )
        self.assert_whitespace_independent_contains(
            multiline_layout,
            "lv_obj_set_style_bg_opa(bottom_bar_, LV_OPA_50, 0);",
            "multiline subtitle background must use 50% opacity",
        )
        self.assert_whitespace_independent_contains(
            multiline_layout,
            "lv_obj_set_style_bg_color(bottom_bar_, lvgl_theme->background_color(), 0);",
            "multiline subtitle background must use the active theme color",
        )

    def test_multiline_subtitle_realigns_after_chat_message_update(self):
        chat_message_update = self.extract_function("SetChatMessage")
        self.assertRegex(
            chat_message_update,
            r"(?s)lv_label_set_text\s*\(\s*chat_message_label_\s*,.*?"
            r"#if\s+CONFIG_USE_MULTILINE_CHAT_MESSAGE.*?"
            r"lv_obj_align\s*\(\s*bottom_bar_\s*,\s*LV_ALIGN_BOTTOM_MID\s*,"
            r"\s*0\s*,\s*0\s*\)\s*;",
            "multiline subtitle bar must be realigned after its text changes",
        )


if __name__ == "__main__":
    unittest.main()
