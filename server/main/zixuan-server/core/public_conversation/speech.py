"""把一轮回复按句子合成语音。

整段回复写完再合成，用户要等模型写完、再等整段合成完才听到第一个字。
这里在模型往下写的同时把已经写完的句子送去合成，第一句合成好就发给客户端播放。
每一段都是一个可以独立解码的完整音频，客户端按到达顺序排队播放。
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

# 一句话结束的标点。英文句号要看后面是不是空白，避免把 3.14 这样的数字切开。
_SENTENCE_END = "。！？!?；;…\n"
# 跟在句末标点后面的引号、括号，归到前一句。
_CLOSERS = "”’」』）)】》\"'"
# 一句话太长又没有句末标点时，退而在这些停顿处切开。
_CLAUSE_END = "，,、：:"

# 太短的句子（“嗯。”“好！”）不单独合成，和后面一句并在一起，省一次请求，语气也更连贯。
MIN_SEGMENT_CHARS = 6
# 超过这个长度还没遇到句末标点，就在最近的停顿处切开，不让用户一直等。
SOFT_SEGMENT_CHARS = 60
# 连停顿都没有时的硬上限。
HARD_SEGMENT_CHARS = 120


def _speakable(text: str) -> int:
    """能读出声的字符数。只有标点或表情的片段交给语音合成会报错或得到空音频。"""
    return sum(1 for char in text if char.isalnum())


class SentenceSplitter:
    def __init__(self) -> None:
        self._buffer = ""

    def feed(self, text: str) -> list[str]:
        self._buffer += text
        segments: list[str] = []
        while True:
            cut = self._next_cut()
            if cut is None:
                return segments
            segments.append(self._buffer[:cut])
            self._buffer = self._buffer[cut:]

    def finish(self) -> list[str]:
        rest, self._buffer = self._buffer, ""
        return [rest] if _speakable(rest) else []

    def _next_cut(self) -> int | None:
        buffer = self._buffer
        index = 0
        while index < len(buffer):
            char = buffer[index]
            ends = char in _SENTENCE_END or (
                char == "." and index + 1 < len(buffer) and buffer[index + 1].isspace()
            )
            if ends:
                end = index + 1
                while end < len(buffer) and (buffer[end] in _SENTENCE_END or buffer[end] in _CLOSERS):
                    end += 1
                # 标点正好在末尾时，下一段可能还跟着引号或连续的标点，等它来了再切。
                if end >= len(buffer):
                    break
                if _speakable(buffer[:end]) >= MIN_SEGMENT_CHARS:
                    return end
                index = end
                continue
            index += 1
        if _speakable(buffer) >= SOFT_SEGMENT_CHARS:
            clause = max(buffer.rfind(mark, 0, len(buffer) - 1) for mark in _CLAUSE_END)
            if clause >= 0 and _speakable(buffer[:clause + 1]) >= MIN_SEGMENT_CHARS:
                return clause + 1
            if _speakable(buffer) >= HARD_SEGMENT_CHARS:
                return len(buffer)
        return None


Synthesize = Callable[[str], Awaitable[tuple[bytes, str]]]
Deliver = Callable[[int, str, bytes, str], Awaitable[bool]]


class SpeechStream:
    """一轮回复的语音流水线：喂进文字，按句合成，按顺序交付。

    合成一次只做一句，保证交付顺序就是说话顺序。某一句合成失败会重试一次，
    再失败就让这一轮失败，和整段合成时的行为一致。
    """

    def __init__(self, synthesize: Synthesize, deliver: Deliver) -> None:
        self._synthesize = synthesize
        self._deliver = deliver
        self._splitter = SentenceSplitter()
        self._queue: asyncio.Queue[str | None] = asyncio.Queue()
        self._task: asyncio.Task[None] | None = None
        self._index = 0
        self._closed = False

    def feed(self, text: str) -> None:
        if self._closed:
            return
        for segment in self._splitter.feed(text):
            self._enqueue(segment)

    async def finish(self) -> None:
        """回复写完了：把剩下的话送去合成，等所有语音交付完。合成失败在这里抛出。"""
        if self._closed:
            return
        self._closed = True
        for segment in self._splitter.finish():
            self._enqueue(segment)
        if self._task is None:
            return
        self._queue.put_nowait(None)
        await self._task

    async def abort(self) -> None:
        """丢掉还没交付的语音。回复被收回、这一轮被取消或出错时调用。"""
        self._closed = True
        task, self._task = self._task, None
        if task is None:
            return
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    def _enqueue(self, segment: str) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run())
        self._queue.put_nowait(segment)

    async def _run(self) -> None:
        while True:
            segment = await self._queue.get()
            if segment is None:
                return
            try:
                audio, mime_type = await self._synthesize(segment)
            except asyncio.CancelledError:
                raise
            except Exception:
                audio, mime_type = await self._synthesize(segment)
            if not await self._deliver(self._index, segment, audio, mime_type):
                return
            self._index += 1
