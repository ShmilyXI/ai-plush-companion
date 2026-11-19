from dataclasses import dataclass
from enum import Enum
import json
import re


class CompanionEmotion(str, Enum):
    NEUTRAL = "neutral"
    HAPPY = "happy"
    GENTLE = "gentle"
    SAD = "sad"
    SURPRISED = "surprised"
    SLEEPY = "sleepy"
    CONCERNED = "concerned"


@dataclass(frozen=True)
class CompanionReplyMetadata:
    emotion: CompanionEmotion = CompanionEmotion.NEUTRAL


class CompanionReplyStreamParser:
    MAX_HEADER = 512
    HEADER_TRANSLATION = str.maketrans({
        "“": '"',
        "”": '"',
    })
    PLAIN_MARKER = "emotion"
    PLAIN_OPENERS = "[【(（<"
    BRACKETED_HEADER = re.compile(
        r"^[\[【(（<]\s*emotion\s*[:：=]\s*[\"']?([A-Za-z_]+)[\"']?\s*[\]】)）>]", re.IGNORECASE)
    PLAIN_HEADER = re.compile(r"^emotion\s*[:：=]\s*[\"']?([A-Za-z_]+)[\"']?\s*$", re.IGNORECASE)

    def __init__(self):
        self._buffer = ""
        self._header_complete = False
        self._has_header = False
        self.metadata = CompanionReplyMetadata()

    @property
    def has_header(self) -> bool:
        return self._has_header

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
                self._has_header = True
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
            # 不是 JSON 头时，只有 emotion 开头的标记才可能是情绪头。模型有时不按约定的 JSON 写，
            # 而是写成 "emotion: happy"、"[emotion: happy]" 或 "【emotion：gentle】"。
            # 其余情况就是正文，立刻放行，不能为了等一个换行把整句话压到最后才输出。
            candidate = stripped_buffer.lower()
            if candidate[:1] in self.PLAIN_OPENERS:
                candidate = candidate[1:].lstrip()
            if not (candidate.startswith(self.PLAIN_MARKER) or self.PLAIN_MARKER.startswith(candidate)):
                return self._fallback()
            bracketed = self.BRACKETED_HEADER.match(stripped_buffer)
            if bracketed is not None:
                return self._accept_plain(bracketed.group(1), stripped_buffer[bracketed.end():])
            newline = self._buffer.find("\n")
            if newline < 0 and len(self._buffer) <= self.MAX_HEADER:
                return []
            if newline < 0:
                return self._fallback()
            plain = self.PLAIN_HEADER.match(self._buffer[:newline].strip())
            if plain is None:
                return self._fallback()
            return self._accept_plain(plain.group(1), self._buffer[newline + 1:])
        try:
            raw = json.loads(header.translate(self.HEADER_TRANSLATION))
            if not isinstance(raw, dict):
                return self._fallback()
            try:
                emotion = CompanionEmotion(raw.get("emotion", "neutral"))
            except (TypeError, ValueError):
                emotion = CompanionEmotion.NEUTRAL
            self.metadata = CompanionReplyMetadata(emotion=emotion)
            self._header_complete = True
            self._has_header = True
            self._buffer = ""
            return [text] if text else []
        except (json.JSONDecodeError, TypeError, ValueError):
            return self._fallback()

    def _accept_plain(self, emotion_name: str, rest: str) -> list[str]:
        try:
            emotion = CompanionEmotion(emotion_name.lower())
        except ValueError:
            emotion = CompanionEmotion.NEUTRAL
        text = rest.lstrip()
        self.metadata = CompanionReplyMetadata(emotion=emotion)
        self._header_complete = True
        self._has_header = True
        self._buffer = ""
        return [text] if text else []

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
