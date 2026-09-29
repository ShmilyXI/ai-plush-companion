from dataclasses import dataclass

from core.companion.reply_protocol import CompanionEmotion, CompanionReplyMetadata


@dataclass(frozen=True)
class CompanionExpression:
    display_emotion: str
    tts_emotion: str
    emotion_scale: int
    speech_rate: int
    pitch: int


class EmotionPolicy:
    _MAP = {
        CompanionEmotion.NEUTRAL: ("neutral", "neutral", 2, 0, 0),
        CompanionEmotion.HAPPY: ("happy", "happy", 3, 8, 1),
        CompanionEmotion.GENTLE: ("relaxed", "neutral", 2, -8, -1),
        CompanionEmotion.SAD: ("sad", "sad", 3, -12, -2),
        CompanionEmotion.SURPRISED: ("surprised", "surprised", 3, 6, 2),
        CompanionEmotion.SLEEPY: ("sleepy", "neutral", 2, -18, -2),
        CompanionEmotion.CONCERNED: ("thinking", "neutral", 2, -10, -1),
    }

    def resolve(self, metadata: CompanionReplyMetadata) -> CompanionExpression:
        display, tts, scale, rate, pitch = self._MAP[metadata.emotion]
        return CompanionExpression(display, tts, scale, rate, pitch)
