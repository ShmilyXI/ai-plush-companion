import queue
import unittest

from core.companion.streaming_reply import CompanionStreamingReply
from core.providers.tts.dto.dto import ContentType, SentenceType


class CompanionStreamingReplyTest(unittest.TestCase):
    def test_first_message_contains_expression_and_header_never_reaches_tts(self):
        messages = queue.Queue()
        reply = CompanionStreamingReply("sentence-id", messages)

        reply.feed('{"emotion":"gentle"}\n你')
        reply.feed("好，我在。")
        reply.finish()

        first = messages.get_nowait()
        middle = [messages.get_nowait(), messages.get_nowait()]
        last = messages.get_nowait()
        self.assertEqual(SentenceType.FIRST, first.sentence_type)
        self.assertEqual("relaxed", first.expression.display_emotion)
        self.assertEqual("你好，我在。", "".join(message.content_detail for message in middle))
        self.assertEqual(SentenceType.LAST, last.sentence_type)

    def test_observers_run_after_first_is_enqueued(self):
        events = []

        class ObservedQueue(queue.Queue):
            def put(self, message, *args, **kwargs):
                events.append(("queue", message.sentence_type, message.content_type))
                return super().put(message, *args, **kwargs)

        reply = CompanionStreamingReply(
            "sentence-id",
            ObservedQueue(),
            on_expression=lambda expression: events.append(
                ("emotion", expression.display_emotion)
            ),
        )

        reply.feed('{"emotion":"gentle"}\n你好')

        self.assertEqual(
            [
                ("queue", SentenceType.FIRST, ContentType.ACTION),
                ("emotion", "relaxed"),
                ("queue", SentenceType.MIDDLE, ContentType.TEXT),
            ],
            events,
        )

    def test_repeated_start_and_feed_notify_observer_once(self):
        expression_events = []
        reply = CompanionStreamingReply(
            "sentence-id",
            queue.Queue(),
            on_expression=expression_events.append,
        )

        reply.feed('{"emotion":"gentle"}\n你')
        reply.start()
        reply.feed("好")
        reply.start()

        self.assertEqual(1, len(expression_events))

    def test_observer_errors_do_not_change_or_repeat_playback(self):
        messages = queue.Queue()
        observer_calls = {"emotion": 0}

        def raise_emotion_error(expression):
            observer_calls["emotion"] += 1
            raise RuntimeError("emotion observer failed")

        reply = CompanionStreamingReply(
            "sentence-id",
            messages,
            on_expression=raise_emotion_error,
        )

        reply.feed('{"emotion":"gentle"}\n你')
        reply.start()
        reply.feed("好")
        reply.finish()

        queued = list(messages.queue)
        self.assertEqual(
            1,
            sum(message.sentence_type == SentenceType.FIRST for message in queued),
        )
        self.assertEqual(
            "你好",
            "".join(
                message.content_detail
                for message in queued
                if message.content_type == ContentType.TEXT
            ),
        )
        self.assertEqual(SentenceType.LAST, queued[-1].sentence_type)
        self.assertEqual({"emotion": 1}, observer_calls)
        self.assertTrue(reply.started)


if __name__ == "__main__":
    unittest.main()
