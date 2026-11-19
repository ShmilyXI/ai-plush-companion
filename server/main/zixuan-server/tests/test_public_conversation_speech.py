import asyncio

import pytest

from core.public_conversation.speech import SentenceSplitter, SpeechStream


def split(*chunks):
    splitter = SentenceSplitter()
    segments = []
    for chunk in chunks:
        segments.extend(splitter.feed(chunk))
    return segments, splitter.finish()


def test_a_sentence_is_released_as_soon_as_the_next_one_starts():
    segments, rest = split("今天适合出门走走。", "顺便", "晒晒太阳吧！")
    assert segments == ["今天适合出门走走。"]
    assert rest == ["顺便晒晒太阳吧！"]


def test_text_split_across_model_chunks_is_joined_in_order():
    segments, rest = split("今天适", "合出门", "走走。顺便晒", "晒太阳吧！好不好？")
    assert segments + rest == ["今天适合出门走走。", "顺便晒晒太阳吧！", "好不好？"]


def test_short_interjections_are_merged_into_the_next_sentence():
    segments, rest = split("嗯。好的！我明白你的意思了。那我们开始吧")
    assert segments == ["嗯。好的！我明白你的意思了。"]
    assert rest == ["那我们开始吧"]


def test_closing_quotes_stay_with_their_sentence():
    segments, rest = split("她说：“今天真的很开心。”然后就回家了。")
    assert segments == ["她说：“今天真的很开心。”"]
    assert rest == ["然后就回家了。"]


def test_decimals_are_not_cut_and_english_sentences_are():
    segments, rest = split("圆周率大约是3.14159左右。The weather is nice. Let us go outside now.")
    assert segments == ["圆周率大约是3.14159左右。", "The weather is nice."]
    assert rest == [" Let us go outside now."]


def test_a_long_run_without_sentence_marks_is_cut_at_a_pause():
    text = "我想了很久" + "，然后又想了一会儿" * 8
    segments, rest = split(text)
    assert segments, "long text must not wait for the end of the reply"
    assert "".join(segments + rest) == text
    assert all(segment.endswith("，") for segment in segments)


def test_nothing_unspeakable_is_sent_to_synthesis():
    segments, rest = split("……", "😊")
    assert segments == [] and rest == []


@pytest.mark.asyncio
async def test_audio_is_delivered_sentence_by_sentence_while_text_is_still_arriving():
    delivered = []
    synthesized = []

    async def synthesize(text):
        synthesized.append(text)
        await asyncio.sleep(0)
        return text.encode(), "audio/mpeg"

    async def deliver(index, text, audio, mime_type):
        delivered.append((index, text, audio, mime_type))
        return True

    stream = SpeechStream(synthesize, deliver)
    stream.feed("今天适合出门走走。顺便")
    for _ in range(5):
        await asyncio.sleep(0)
    # 第二句还没写完，第一句的语音已经交付了。
    assert [item[1] for item in delivered] == ["今天适合出门走走。"]
    stream.feed("晒晒太阳吧！")
    await stream.finish()
    assert [(item[0], item[1]) for item in delivered] == [(0, "今天适合出门走走。"), (1, "顺便晒晒太阳吧！")]
    assert delivered[0][3] == "audio/mpeg"


@pytest.mark.asyncio
async def test_one_failed_sentence_is_retried_and_a_second_failure_fails_the_turn():
    attempts = []

    async def flaky(text):
        attempts.append(text)
        if len(attempts) == 1:
            raise RuntimeError("temporary")
        return b"ok", "audio/mpeg"

    delivered = []

    async def deliver(index, text, audio, mime_type):
        delivered.append(text)
        return True

    stream = SpeechStream(flaky, deliver)
    stream.feed("今天适合出门走走。")
    await stream.finish()
    assert delivered == ["今天适合出门走走。"] and len(attempts) == 2

    async def broken(_text):
        raise RuntimeError("TTS 未返回音频")

    stream = SpeechStream(broken, deliver)
    stream.feed("今天适合出门走走。")
    with pytest.raises(RuntimeError):
        await stream.finish()


@pytest.mark.asyncio
async def test_abort_drops_audio_that_has_not_been_delivered():
    delivered = []
    release = asyncio.Event()

    async def slow(text):
        await release.wait()
        return b"audio", "audio/mpeg"

    async def deliver(index, text, audio, mime_type):
        delivered.append(text)
        return True

    stream = SpeechStream(slow, deliver)
    stream.feed("今天适合出门走走。顺便晒晒太阳吧！好不好？")
    await asyncio.sleep(0)
    await stream.abort()
    release.set()
    await asyncio.sleep(0)
    assert delivered == []
    # 已经丢弃的流水线再结束一次不会出错，也不会补发。
    await stream.finish()
    assert delivered == []
