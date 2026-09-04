import asyncio
import json
import unittest

from core.companion.emotion_policy import EmotionPolicy
from core.companion.reply_protocol import CompanionEmotion, CompanionReplyMetadata
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.utils.textUtils import send_companion_emotion


class EmotionPolicyTest(unittest.TestCase):
    def test_gentle_maps_to_display_and_tts(self):
        expression = EmotionPolicy().resolve(
            CompanionReplyMetadata(CompanionEmotion.GENTLE, "breathe")
        )
        self.assertEqual("relaxed", expression.display_emotion)
        self.assertEqual("neutral", expression.tts_emotion)
        self.assertEqual(-8, expression.speech_rate)
        self.assertEqual("breathe", expression.cue)

    def test_disallowed_cue_is_absent(self):
        expression = EmotionPolicy().resolve(CompanionReplyMetadata())
        self.assertIsNone(expression.cue)

    def test_tts_message_carries_expression(self):
        expression = EmotionPolicy().resolve(CompanionReplyMetadata())
        message = TTSMessageDTO(
            "sentence",
            SentenceType.FIRST,
            ContentType.TEXT,
            content_detail="你好",
            expression=expression,
        )
        self.assertIs(expression, message.expression)

    def test_display_emotion_uses_structured_expression(self):
        class WebSocket:
            def __init__(self):
                self.payload = None

            async def send(self, payload):
                self.payload = json.loads(payload)

        class Connection:
            websocket = WebSocket()
            session_id = "session"

        expression = EmotionPolicy().resolve(
            CompanionReplyMetadata(CompanionEmotion.GENTLE)
        )
        connection = Connection()

        asyncio.run(send_companion_emotion(connection, expression))

        self.assertEqual("relaxed", connection.websocket.payload["emotion"])
        self.assertEqual("", connection.websocket.payload["text"])


if __name__ == "__main__":
    unittest.main()
