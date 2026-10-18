import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import yaml

from core.companion.reply_protocol import (
    CompanionEmotion,
    CompanionReplyStreamParser,
)
from core.utils.prompt_manager import PromptManager


class CompanionReplyStreamParserTest(unittest.TestCase):
    def test_split_header_is_removed_and_text_is_streamed(self):
        parser = CompanionReplyStreamParser()
        self.assertEqual([], parser.feed('{"emotion":"gen'))
        self.assertEqual([], parser.feed('tle"}\n'))
        self.assertEqual(["我在这里。"], parser.feed("我在这里。"))
        self.assertEqual(CompanionEmotion.GENTLE, parser.metadata.emotion)

    def test_multiline_header_is_buffered_until_json_object_closes(self):
        parser = CompanionReplyStreamParser()
        self.assertEqual([], parser.feed('{"emotion":"sad",\n'))
        self.assertEqual(
            ["我在。"],
            parser.feed('"extra":null}\n我在。'),
        )
        self.assertEqual(CompanionEmotion.SAD, parser.metadata.emotion)

    def test_brace_inside_string_does_not_end_json_header(self):
        parser = CompanionReplyStreamParser()

        output = parser.feed(
            '{"note":"先别用 } 结束","emotion":"sad"}\n我在。'
        )

        self.assertEqual(["我在。"], output)
        self.assertEqual(CompanionEmotion.SAD, parser.metadata.emotion)

    def test_nested_json_does_not_leak_metadata_into_spoken_text(self):
        parser = CompanionReplyStreamParser()

        output = parser.feed(
            '{"extra":{"source":"model"},"emotion":"gentle"}\n抱抱你。'
        )

        self.assertEqual(["抱抱你。"], output)
        self.assertEqual(CompanionEmotion.GENTLE, parser.metadata.emotion)

    def test_stale_cue_key_in_header_is_ignored(self):
        parser = CompanionReplyStreamParser()

        output = parser.feed('{"emotion":"gentle","cue":"sigh"}\n我在。')

        self.assertEqual(["我在。"], output)
        self.assertEqual(CompanionEmotion.GENTLE, parser.metadata.emotion)
        self.assertFalse(hasattr(parser.metadata, "cue"))

    def test_smart_quote_header_is_removed_instead_of_spoken(self):
        parser = CompanionReplyStreamParser()

        output = parser.feed(
            "{“emotion”:“sad”}\n时间过得真快。"
        )

        self.assertEqual(["时间过得真快。"], output)
        self.assertEqual(CompanionEmotion.SAD, parser.metadata.emotion)

    def test_model_control_tag_is_removed_instead_of_spoken(self):
        parser = CompanionReplyStreamParser()

        output = parser.feed("{:careful} 我重新回答你。")

        self.assertEqual(["我重新回答你。"], output)
        self.assertEqual(CompanionEmotion.NEUTRAL, parser.metadata.emotion)

    def test_invalid_header_falls_back_to_neutral_plain_text(self):
        parser = CompanionReplyStreamParser()
        output = parser.feed("这不是 JSON。") + parser.finish()
        self.assertEqual(["这不是 JSON。"], output)
        self.assertEqual(CompanionEmotion.NEUTRAL, parser.metadata.emotion)

    def test_unknown_values_are_safely_normalized(self):
        parser = CompanionReplyStreamParser()
        output = parser.feed('{"emotion":"rage"}\n你好')
        self.assertEqual(["你好"], output)
        self.assertEqual(CompanionEmotion.NEUTRAL, parser.metadata.emotion)

    def test_non_object_json_header_falls_back_without_crashing(self):
        parser = CompanionReplyStreamParser()

        output = parser.feed('["not","metadata"]\n你好')

        self.assertEqual(['["not","metadata"]\n你好'], output)
        self.assertEqual(CompanionEmotion.NEUTRAL, parser.metadata.emotion)

    def test_has_header_true_when_json_header_parsed(self):
        parser = CompanionReplyStreamParser()
        self.assertFalse(parser.has_header)
        parser.feed('{"emotion":"sad"}\n我在。')
        self.assertTrue(parser.has_header)

    def test_has_header_true_for_control_tag_and_smart_quotes(self):
        tag_parser = CompanionReplyStreamParser()
        tag_parser.feed("{:careful} 我重新回答你。")
        self.assertTrue(tag_parser.has_header)

        smart_quote_parser = CompanionReplyStreamParser()
        smart_quote_parser.feed("{“emotion”:“sad”}\n时间过得真快。")
        self.assertTrue(smart_quote_parser.has_header)

    def test_has_header_false_after_plain_text_fallback(self):
        parser = CompanionReplyStreamParser()
        parser.feed("这不是 JSON。")
        parser.finish()
        self.assertFalse(parser.has_header)


class CompanionReplyPromptTest(unittest.TestCase):
    def test_enabled_companion_uses_persona_prompt_before_reply_contract(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {
            "companion": {
                "enabled": True,
                "persona_prompt": "治愈型朋友提示词",
            }
        }

        prompt = manager.add_companion_reply_contract("毒舌角色提示词")

        self.assertTrue(prompt.startswith("毒舌角色提示词\n\n治愈型朋友提示词"))
        self.assertIn("毒舌角色提示词", prompt)
        self.assertIn("<companion_reply_protocol>", prompt)

    def test_enabled_companion_with_empty_persona_keeps_passed_prompt(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {
            "companion": {
                "enabled": True,
                "persona_prompt": "  ",
            }
        }

        prompt = manager.add_companion_reply_contract("原有角色提示词")

        self.assertTrue(prompt.startswith("原有角色提示词"))
        self.assertIn("<companion_reply_protocol>", prompt)

    def test_enhanced_prompt_keeps_template_around_companion_persona(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {
            "companion": {
                "enabled": True,
                "persona_prompt": "治愈型朋友提示词",
            }
        }
        manager.base_prompt_template = "模板开头\n{{ base_prompt }}\n模板结尾"
        manager.context_data = ""
        manager.cache_manager = Mock()
        manager.CacheType = SimpleNamespace(DEVICE_PROMPT="device_prompt")
        manager.logger = Mock()
        manager._get_current_time_info = lambda: ("今天", "星期二", "农历")

        prompt = manager.build_enhanced_prompt("毒舌角色提示词", "device-id")

        self.assertTrue(prompt.startswith("模板开头\n毒舌角色提示词\n\n治愈型朋友提示词\n模板结尾"))
        self.assertIn("毒舌角色提示词", prompt)
        self.assertIn("<companion_reply_protocol>", prompt)

    def test_default_config_defines_healing_companion_persona(self):
        config_path = Path(__file__).parents[1] / "config.yaml"
        config = yaml.safe_load(config_path.read_text(encoding="utf-8"))

        self.assertIn("persona_prompt", config["companion"])
        persona_prompt = config["companion"]["persona_prompt"]

        self.assertIn("治愈型陪伴朋友", persona_prompt)
        self.assertIn("先接住用户的情绪", persona_prompt)
        self.assertIn("不嘲讽", persona_prompt)
        self.assertIn("不要每轮都给建议或追问", persona_prompt)
        self.assertIn("不要诱导用户依赖", persona_prompt)
        self.assertIn("现实支持", persona_prompt)

    def test_enabled_companion_adds_reply_contract(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {"companion": {"enabled": True}}

        prompt = manager.add_companion_reply_contract("陪伴提示词")

        self.assertTrue(prompt.startswith("陪伴提示词"))
        self.assertIn("<companion_reply_protocol>", prompt)
        self.assertIn('{"emotion":"gentle"}', prompt)

    def test_reply_contract_only_allows_emotion(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {"companion": {"enabled": True}}

        prompt = manager.add_companion_reply_contract("陪伴提示词")

        self.assertIn("JSON 只允许 emotion", prompt)
        self.assertIn("emotion 只能是 neutral、happy、gentle、sad、surprised、sleepy、concerned", prompt)
        self.assertNotIn("cue", prompt)
        self.assertIn("第二行开始是给用户听到的正文，两部分缺一不可", prompt)

    def test_default_config_has_no_cue_files(self):
        config_path = Path(__file__).parents[1] / "config.yaml"
        config = yaml.safe_load(config_path.read_text(encoding="utf-8"))

        self.assertNotIn("cue_files", config["companion"])

    def test_disabled_companion_keeps_prompt_unchanged(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {}

        self.assertEqual("普通提示词", manager.add_companion_reply_contract("普通提示词"))

    def test_screen_expression_without_companion_adds_expression_contract(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {
            "companion": {
                "enabled": False,
                "screen_expression_enabled": True,
            }
        }

        prompt = manager.add_companion_reply_contract("普通角色提示词")

        self.assertTrue(prompt.startswith("普通角色提示词"))
        self.assertIn("<screen_expression_protocol>", prompt)
        self.assertIn('{"emotion":"gentle"}', prompt)
        self.assertIn(
            "emotion 只能是 neutral、happy、gentle、sad、surprised、sleepy、concerned",
            prompt,
        )

    def test_screen_expression_off_keeps_prompt_unchanged(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {
            "companion": {
                "enabled": False,
                "screen_expression_enabled": False,
            }
        }

        self.assertEqual(
            "普通提示词",
            manager.add_companion_reply_contract("普通提示词"),
        )

    def test_companion_contract_still_added_when_screen_expression_off(self):
        manager = PromptManager.__new__(PromptManager)
        manager.config = {
            "companion": {
                "enabled": True,
                "screen_expression_enabled": False,
            }
        }

        prompt = manager.add_companion_reply_contract("陪伴提示词")

        self.assertTrue(prompt.startswith("陪伴提示词"))
        self.assertIn("<companion_reply_protocol>", prompt)

    def test_weather_context_is_not_fetched_without_weather_function(self):
        manager = PromptManager.__new__(PromptManager)
        manager.base_prompt_template = "{{ weather_info }}"
        manager._get_location_info = Mock(return_value="广州")
        manager._get_weather_info = Mock()
        manager.logger = Mock()
        manager.config = {
            "selected_module": {"Intent": "Intent_function_call"},
            "Intent": {"Intent_function_call": {"functions": ["handle_exit_intent"]}},
        }

        manager.update_context_info(SimpleNamespace(), "127.0.0.1")

        manager._get_weather_info.assert_not_called()


if __name__ == "__main__":
    unittest.main()
