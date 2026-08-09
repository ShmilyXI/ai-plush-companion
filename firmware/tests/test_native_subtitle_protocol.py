import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class NativeSubtitleProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / "main/application.cc").read_text(encoding="utf-8")
        cls.masked_source = cls.mask_non_code(cls.source)
        cls.semantic_source = cls.mask_comments_and_raw_strings(cls.source)

    @staticmethod
    def mask_non_code(source):
        return NativeSubtitleProtocolTest.mask_source(source, mask_literals=True)

    @staticmethod
    def mask_comments_and_raw_strings(source):
        return NativeSubtitleProtocolTest.mask_source(source, mask_literals=False)

    @staticmethod
    def mask_source(source, mask_literals):
        masked = list(source)

        def blank(start, end):
            for index in range(start, end):
                if masked[index] != "\n":
                    masked[index] = " "

        index = 0
        while index < len(source):
            if source.startswith("//", index):
                end = source.find("\n", index)
                end = len(source) if end == -1 else end
                blank(index, end)
                index = end
            elif source.startswith("/*", index):
                end = source.find("*/", index + 2)
                end = len(source) if end == -1 else end + 2
                blank(index, end)
                index = end
            elif source.startswith('R"', index):
                delimiter_end = index + 2
                raw_string_end = None
                while delimiter_end < len(source) and delimiter_end - (index + 2) <= 16:
                    character = source[delimiter_end]
                    if character == "(":
                        delimiter = source[index + 2 : delimiter_end]
                        closing = ")" + delimiter + '"'
                        end = source.find(closing, delimiter_end + 1)
                        if end != -1:
                            end += len(closing)
                            blank(index, end)
                            raw_string_end = end
                            break
                        break
                    if character in " \t\v\f\r\n()\\":
                        break
                    delimiter_end += 1
                else:
                    index += 1
                    continue
                if raw_string_end is not None:
                    index = raw_string_end
                    continue
                index += 1
            elif source[index] in ('"', "'"):
                quote = source[index]
                end = index + 1
                while end < len(source):
                    if source[end] == "\\":
                        end += 2
                    elif source[end] == quote:
                        end += 1
                        break
                    else:
                        end += 1
                if mask_literals:
                    blank(index, min(end, len(source)))
                index = end
            else:
                index += 1
        return "".join(masked)

    def extract_braced_block(self, masked, start, description):
        opening_brace = masked.find("{", start)
        self.assertNotEqual(opening_brace, -1, f"{description} has no body")
        depth = 0
        for index in range(opening_brace, len(masked)):
            if masked[index] == "{":
                depth += 1
            elif masked[index] == "}":
                depth -= 1
                if depth == 0:
                    return opening_brace + 1, index
        self.fail(f"{description} body is not closed")

    def extract_branch(
        self, source, masked, semantic, masked_pattern, source_pattern, description
    ):
        for match in re.finditer(masked_pattern, masked, re.DOTALL):
            if re.match(source_pattern, source[match.start() :], re.DOTALL):
                start, end = self.extract_braced_block(masked, match.end(), description)
                return source[start:end], masked[start:end], semantic[start:end]
        self.fail(f"missing {description}")

    def extract_message_handler(self):
        pattern = r"protocol_->OnIncomingJson\s*\(\s*\[[^\]]*\]\s*\(\s*const\s+cJSON\*\s+root\s*\)"
        return self.extract_branch(
            self.source,
            self.masked_source,
            self.semantic_source,
            pattern,
            pattern,
            "incoming JSON handler",
        )

    def extract_type_branch(self, source, masked, semantic, message_type, prefix):
        masked_pattern = (
            rf"{prefix}\s*\(\s*strcmp\s*\(\s*type->valuestring\s*,\s*\)\s*==\s*0\s*\)"
        )
        source_pattern = (
            rf"{prefix}\s*\(\s*strcmp\s*\(\s*type->valuestring\s*,\s*\"{message_type}\"\s*\)\s*==\s*0\s*\)"
        )
        return self.extract_branch(
            source, masked, semantic, masked_pattern, source_pattern, f"{message_type} branch"
        )

    def extract_sentence_start_branch(self, source, masked, semantic):
        masked_pattern = r"else\s+if\s*\(\s*strcmp\s*\(\s*state->valuestring\s*,\s*\)\s*==\s*0\s*\)"
        source_pattern = r"else\s+if\s*\(\s*strcmp\s*\(\s*state->valuestring\s*,\s*\"sentence_start\"\s*\)\s*==\s*0\s*\)"
        return self.extract_branch(
            source,
            masked,
            semantic,
            masked_pattern,
            source_pattern,
            "tts sentence_start branch",
        )

    def extract_text_guard(self, source, masked, semantic):
        pattern = r"if\s*\(\s*cJSON_IsString\s*\(\s*text\s*\)\s*\)"
        return self.extract_branch(source, masked, semantic, pattern, pattern, "text string guard")

    def subtitle_pattern(self, role):
        copied_text = r"(?:text->valuestring|std::string\s*\(\s*text->valuestring\s*\))"
        explicit_copy = (
            rf"std::string\s+message\s*(?:=\s*{copied_text}|\(\s*{copied_text}\s*\)|\{{\s*{copied_text}\s*\}})\s*;"
        )
        set_chat_message = (
            rf"display->SetChatMessage\s*\(\s*\"{role}\"\s*,\s*message\.c_str\s*\(\s*\)\s*\)"
        )
        captured_declared_copy = (
            rf"{explicit_copy}.*?Schedule\s*\(\s*\[\s*display\s*,\s*message\s*\]"
            rf"\s*\(\s*\)\s*\{{.*?{set_chat_message}"
        )
        captured_std_string = (
            rf"Schedule\s*\(\s*\[\s*display\s*,\s*message\s*=\s*std::string\s*\(\s*text->valuestring\s*\)\s*\]"
            rf"\s*\(\s*\)\s*\{{.*?{set_chat_message}"
        )
        return rf"(?s)(?:{captured_declared_copy}|{captured_std_string})"

    def assert_subtitle(self, semantic_text_guard, role):
        self.assertRegex(
            semantic_text_guard,
            self.subtitle_pattern(role),
            f"{role} subtitle must copy text and use that captured message inside its string guard",
        )

    def test_mask_non_code_preserves_offsets_and_newlines(self):
        source = 'code "// value" /* block\ncomment */ \'{\' // line\nnext'
        masked = self.mask_non_code(source)
        self.assertEqual(len(masked), len(source))
        self.assertEqual(masked.count("\n"), source.count("\n"))
        self.assertNotIn("value", masked)
        self.assertIn("next", masked)

    def test_mask_non_code_preserves_raw_strings_and_following_code(self):
        source = 'auto value = R"a"b(// value { /* still value */)a"b"; keep(); // hidden\nmessage'
        masked = self.mask_non_code(source)
        self.assertEqual(len(masked), len(source))
        self.assertNotIn("still value", masked)
        self.assertIn("keep();", masked)
        self.assertIn("message", masked)

    def test_copy_pattern_accepts_explicit_std_string_initializers(self):
        pattern = self.subtitle_pattern("user")
        for declaration in (
            "std::string message = text->valuestring;",
            "std::string message = std::string(text->valuestring);",
            "std::string message(text->valuestring);",
            "std::string message{text->valuestring};",
        ):
            source = declaration + ' Schedule([display, message]() { display->SetChatMessage("user", message.c_str()); });'
            with self.subTest(declaration=declaration):
                self.assertRegex(source, pattern)

    def test_copy_pattern_rejects_untyped_text_copies(self):
        pattern = self.subtitle_pattern("user")
        for source in (
            'Schedule([display, message = text->valuestring]() { display->SetChatMessage("user", message.c_str()); });',
            'auto message = text->valuestring; Schedule([display, message]() { display->SetChatMessage("user", message.c_str()); });',
        ):
            with self.subTest(source=source):
                self.assertNotRegex(source, pattern)

    def test_structure_ignores_pseudocode_in_strings(self):
        source = (
            '"if (cJSON_IsString(text)) { }"; '
            'R"tag(if (cJSON_IsString(text)) { })tag"; '
            'if (cJSON_IsString(text)) { std::string message(text->valuestring); } '
            '"}";'
        )
        masked = self.mask_non_code(source)
        semantic = self.mask_comments_and_raw_strings(source)
        block, _, _ = self.extract_text_guard(source, masked, semantic)
        self.assertIn("std::string message(text->valuestring);", block)

    def test_subtitle_must_remain_inside_text_guard(self):
        source = (
            'if (cJSON_IsString(text)) { ESP_LOGI(TAG, "text"); } '
            'std::string message(text->valuestring); '
            'Schedule([display, message]() { display->SetChatMessage("user", message.c_str()); });'
        )
        masked = self.mask_non_code(source)
        semantic = self.mask_comments_and_raw_strings(source)
        _, _, guard = self.extract_text_guard(source, masked, semantic)
        with self.assertRaises(AssertionError):
            self.assert_subtitle(guard, "user")

    def test_subtitle_rejects_block_comment_decoy(self):
        source = (
            "if (cJSON_IsString(text)) { "
            '/* std::string message(text->valuestring); Schedule([display, message]() { display->SetChatMessage("user", message.c_str()); }); */ '
            "}"
        )
        masked = self.mask_non_code(source)
        semantic = self.mask_comments_and_raw_strings(source)
        _, _, guard = self.extract_text_guard(source, masked, semantic)
        with self.assertRaises(AssertionError):
            self.assert_subtitle(guard, "user")

    def test_subtitle_rejects_raw_string_decoy(self):
        source = (
            "if (cJSON_IsString(text)) { "
            'R"tag(std::string message(text->valuestring); Schedule([display, message]() { display->SetChatMessage("user", message.c_str()); });)tag" '
            "}"
        )
        masked = self.mask_non_code(source)
        semantic = self.mask_comments_and_raw_strings(source)
        _, _, guard = self.extract_text_guard(source, masked, semantic)
        with self.assertRaises(AssertionError):
            self.assert_subtitle(guard, "user")

    def test_stt_branch_forwards_text_as_user_subtitle(self):
        handler, handler_mask, handler_semantic = self.extract_message_handler()
        stt, stt_mask, stt_semantic = self.extract_type_branch(
            handler, handler_mask, handler_semantic, "stt", r"else\s+if"
        )
        _, _, text_guard = self.extract_text_guard(stt, stt_mask, stt_semantic)
        self.assert_subtitle(text_guard, "user")

    def test_tts_sentence_start_branch_forwards_text_as_assistant_subtitle(self):
        handler, handler_mask, handler_semantic = self.extract_message_handler()
        tts, tts_mask, tts_semantic = self.extract_type_branch(
            handler, handler_mask, handler_semantic, "tts", r"if"
        )
        sentence_start, sentence_start_mask, sentence_start_semantic = (
            self.extract_sentence_start_branch(tts, tts_mask, tts_semantic)
        )
        _, _, text_guard = self.extract_text_guard(
            sentence_start, sentence_start_mask, sentence_start_semantic
        )
        self.assert_subtitle(text_guard, "assistant")


if __name__ == "__main__":
    unittest.main()
