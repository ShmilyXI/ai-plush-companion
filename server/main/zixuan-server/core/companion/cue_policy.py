import json
import re
from pathlib import Path

from core.companion.reply_protocol import ALLOWED_CUES


class CuePolicy:
    DEFAULT_RESOURCE_ROOT = Path("config/assets/companion")
    _NEGATED = re.compile(
        r"(?:不|没|无|没有|不用|不再|不用再|并不|并没有|不怎么|一点也不)"
        r"(?:再|那么|很|太|特别)?$"
    )
    _SIGNALS = (
        ("breathe", ("焦虑", "紧张", "很慌", "慌乱", "心跳很快", "喘不过气", "脑子停不下来")),
        ("hesitate", ("不知道该不该", "难开口", "犹豫", "迟疑", "不好意思说")),
        ("sigh", ("很累", "好累", "疲惫", "加班", "失落", "难过", "沮丧", "无奈", "做不好", "不开心", "不太开心")),
        ("laugh", ("太开心", "好开心", "成功了", "终于解决", "太好了", "哈哈", "好笑")),
    )

    def __init__(self, cue_files, resource_root=None):
        self.cue_files = cue_files if isinstance(cue_files, dict) else {}
        self.resource_root = Path(
            resource_root or self.DEFAULT_RESOURCE_ROOT
        ).resolve()

    def select(self, requested_cue, user_input):
        if requested_cue is not None:
            return requested_cue
        text = self._extract_text(user_input)
        for cue, signals in self._SIGNALS:
            if any(self._has_non_negated_signal(text, signal) for signal in signals):
                return cue
        return None

    @staticmethod
    def is_compatible(cue, display_emotion):
        conflicts = {
            "happy": {"sigh", "hesitate", "breathe"},
            "sad": {"laugh"},
            "sleepy": {"laugh"},
            "thinking": {"laugh"},
        }
        return cue is None or cue not in conflicts.get(display_emotion, set())

    def resolve(self, cue):
        if cue not in ALLOWED_CUES:
            return None
        path = self.cue_files.get(cue)
        if not isinstance(path, str) or not path.strip() or "\x00" in path:
            return None
        try:
            relative_path = Path(path)
            if relative_path.is_absolute() or ".." in relative_path.parts:
                return None
            candidates = (
                (Path.cwd() / relative_path).resolve(),
                (self.resource_root / relative_path).resolve(),
            )
        except (OSError, RuntimeError, ValueError):
            return None
        for candidate in candidates:
            try:
                candidate.relative_to(self.resource_root)
            except ValueError:
                continue
            if candidate.is_file():
                return str(candidate)
        return None

    @staticmethod
    def _extract_text(user_input):
        if not isinstance(user_input, str):
            return ""
        try:
            value = json.loads(user_input)
        except json.JSONDecodeError:
            return user_input
        if isinstance(value, dict) and isinstance(value.get("content"), str):
            return value["content"]
        return user_input

    @classmethod
    def _has_non_negated_signal(cls, text, signal):
        start = 0
        while True:
            index = text.find(signal, start)
            if index < 0:
                return False
            prefix = text[max(0, index - 10):index]
            if cls._NEGATED.search(prefix) is None:
                return True
            start = index + len(signal)
