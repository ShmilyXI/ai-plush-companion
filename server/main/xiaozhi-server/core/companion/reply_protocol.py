from dataclasses import dataclass
from enum import Enum
import json
from typing import Optional


class CompanionEmotion(str, Enum):
    NEUTRAL = "neutral"
    HAPPY = "happy"
    GENTLE = "gentle"
    SAD = "sad"
    SURPRISED = "surprised"
    SLEEPY = "sleepy"
    CONCERNED = "concerned"


ALLOWED_CUES = {"laugh", "sigh", "hesitate", "breathe"}


@dataclass(frozen=True)
class CompanionReplyMetadata:
    emotion: CompanionEmotion = CompanionEmotion.NEUTRAL
    cue: Optional[str] = None


class CompanionReplyStreamParser:
    MAX_HEADER = 512
    HEADER_TRANSLATION = str.maketrans({
        "“": '"',
        "”": '"',
    })

    def __init__(self):
        self._buffer = ""
        self._header_complete = False
        self.metadata = CompanionReplyMetadata()

    def feed(self, chunk: str) -> list[str]:
        if not chunk:
            return []
        if self._header_complete:
            return [chunk]
        self._buffer += chunk
        stripped_buffer = self._buffer.lstrip()
        if stripped_buffer.startswith("{:"):
            tag_end = stripped_buffer.find("}")
            if tag_end < 0:
                if len(self._buffer) <= self.MAX_HEADER:
                    return []
                return self._fallback()
            tag = stripped_buffer[2:tag_end]
            if tag and len(tag) <= 32 and all(
                character.isascii()
                and (character.isalnum() or character in "_-")
                for character in tag
            ):
                text = stripped_buffer[tag_end + 1:].lstrip()
                self._buffer = ""
                self._header_complete = True
                return [text] if text else []
        if self._buffer.lstrip().startswith("{"):
            leading_space = len(self._buffer) - len(self._buffer.lstrip())
            normalized_buffer = self._buffer.translate(self.HEADER_TRANSLATION)
            try:
                raw, object_end = json.JSONDecoder().raw_decode(
                    normalized_buffer[leading_space:]
                )
            except json.JSONDecodeError:
                if len(self._buffer) <= self.MAX_HEADER:
                    return []
                return self._fallback()
            if not isinstance(raw, dict):
                return self._fallback()
            object_end += leading_space
            header = self._buffer[:object_end].strip()
            text = self._buffer[object_end:].lstrip("\r\n")
        else:
            newline = self._buffer.find("\n")
            if newline < 0 and len(self._buffer) <= self.MAX_HEADER:
                return []
            if newline < 0:
                return self._fallback()
            header = self._buffer[:newline].strip()
            text = self._buffer[newline + 1:]
        try:
            raw = json.loads(header.translate(self.HEADER_TRANSLATION))
            if not isinstance(raw, dict):
                return self._fallback()
            try:
                emotion = CompanionEmotion(raw.get("emotion", "neutral"))
            except (TypeError, ValueError):
                emotion = CompanionEmotion.NEUTRAL
            cue = raw.get("cue")
            if not isinstance(cue, str) or cue not in ALLOWED_CUES:
                cue = None
            self.metadata = CompanionReplyMetadata(emotion=emotion, cue=cue)
            self._header_complete = True
            self._buffer = ""
            return [text] if text else []
        except (json.JSONDecodeError, TypeError, ValueError):
            return self._fallback()

    def finish(self) -> list[str]:
        if self._header_complete or not self._buffer:
            return []
        return self._fallback()

    def _fallback(self) -> list[str]:
        text = self._buffer
        self._buffer = ""
        self._header_complete = True
        self.metadata = CompanionReplyMetadata()
        return [text] if text else []


def strip_companion_reply_metadata(text: str) -> str:
    parser = CompanionReplyStreamParser()
    parts = parser.feed(text or "")
    parts.extend(parser.finish())
    return "".join(parts)
