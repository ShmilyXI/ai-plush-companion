import queue
import tempfile
import unittest
from pathlib import Path

from core.companion.streaming_reply import CompanionStreamingReply
from core.providers.tts.dto.dto import ContentType, SentenceType


class CompanionStreamingReplyTest(unittest.TestCase):
    def test_first_message_contains_expression_and_header_never_reaches_tts(self):
        messages = queue.Queue()
        reply = CompanionStreamingReply("sentence-id", messages)

        reply.feed('{"emotion":"gentle","cue":null}\n你')
        reply.feed("好，我在。")
        reply.finish()

        first = messages.get_nowait()
        middle = [messages.get_nowait(), messages.get_nowait()]
        last = messages.get_nowait()
        self.assertEqual(SentenceType.FIRST, first.sentence_type)
        self.assertEqual("relaxed", first.expression.display_emotion)
        self.assertEqual("你好，我在。", "".join(message.content_detail for message in middle))
        self.assertEqual(SentenceType.LAST, last.sentence_type)

    def test_only_one_configured_cue_is_enqueued(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
            messages = queue.Queue()
            reply = CompanionStreamingReply(
                "sentence-id",
                messages,
                companion_config={"cue_files": {"sigh": "sigh.wav"}},
                cue_resource_root=directory,
            )

            reply.feed('{"emotion":"gentle","cue":"sigh"}\n你')
            reply.feed("好")
            reply.feed("。")
            reply.finish()

            queued = list(messages.queue)
            cues = [message for message in queued if message.content_type == ContentType.FILE]
            self.assertEqual(1, len(cues))
            self.assertEqual(str(cue_path.resolve()), cues[0].content_file)

    def test_strong_user_emotion_supplies_missing_model_cue(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
            messages = queue.Queue()
            reply = CompanionStreamingReply(
                "sentence-id",
                messages,
                companion_config={"cue_files": {"sigh": "sigh.wav"}},
                cue_resource_root=directory,
                user_input='{"content":"我真的很累。"}',
            )

            reply.feed('{"emotion":"neutral","cue":null}\n我在这里。')
            reply.finish()

            queued = list(messages.queue)
            cues = [message for message in queued if message.content_type == ContentType.FILE]
            self.assertEqual(1, len(cues))
            self.assertEqual("sigh", cues[0].expression.cue)
            self.assertEqual("sigh", queued[0].expression.cue)

    def test_incompatible_model_cue_is_not_played(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
            messages = queue.Queue()
            reply = CompanionStreamingReply(
                "sentence-id",
                messages,
                companion_config={"cue_files": {"sigh": "sigh.wav"}},
                cue_resource_root=directory,
            )

            reply.feed('{"emotion":"happy","cue":"sigh"}\n太好了。')
            reply.finish()

            queued = list(messages.queue)
            cues = [message for message in queued if message.content_type == ContentType.FILE]
            self.assertEqual([], cues)
            self.assertIsNone(queued[0].expression.cue)

    def test_observers_run_after_first_and_file_are_enqueued(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
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
                on_cue_enqueued=lambda expression, path: events.append(
                    (
                        "cue",
                        expression.display_emotion,
                        expression.cue,
                        Path(path).name,
                    )
                ),
                companion_config={"cue_files": {"sigh": "sigh.wav"}},
                cue_resource_root=directory,
            )

            reply.feed('{"emotion":"gentle","cue":"sigh"}\n你好')

            self.assertEqual(
                [
                    ("queue", SentenceType.FIRST, ContentType.ACTION),
                    ("emotion", "relaxed"),
                    ("queue", SentenceType.MIDDLE, ContentType.FILE),
                    ("cue", "relaxed", "sigh", "sigh.wav"),
                    ("queue", SentenceType.MIDDLE, ContentType.TEXT),
                ],
                events,
            )

    def test_repeated_start_and_feed_notify_each_observer_once(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
            expression_events = []
            cue_events = []
            reply = CompanionStreamingReply(
                "sentence-id",
                queue.Queue(),
                on_expression=expression_events.append,
                on_cue_enqueued=lambda expression, path: cue_events.append(
                    (expression.cue, path)
                ),
                companion_config={"cue_files": {"sigh": "sigh.wav"}},
                cue_resource_root=directory,
            )

            reply.feed('{"emotion":"gentle","cue":"sigh"}\n你')
            reply.start()
            reply.feed("好")
            reply.start()

            self.assertEqual(1, len(expression_events))
            self.assertEqual([("sigh", str(cue_path.resolve()))], cue_events)

    def test_missing_or_unrequested_cue_does_not_notify_observer(self):
        with tempfile.TemporaryDirectory() as directory:
            for cue, cue_files in (
                (None, {}),
                ("sigh", {"sigh": "missing.wav"}),
            ):
                with self.subTest(cue=cue):
                    cue_events = []
                    reply = CompanionStreamingReply(
                        "sentence-id",
                        queue.Queue(),
                        on_cue_enqueued=lambda expression, path: cue_events.append(
                            (expression.cue, path)
                        ),
                        companion_config={"cue_files": cue_files},
                        cue_resource_root=directory,
                    )

                    reply.feed(
                        '{"emotion":"gentle","cue":%s}\n你好'
                        % ("null" if cue is None else '"sigh"')
                    )

                    self.assertEqual([], cue_events)

    def test_observer_errors_do_not_change_or_repeat_playback(self):
        with tempfile.TemporaryDirectory() as directory:
            cue_path = Path(directory) / "sigh.wav"
            cue_path.write_bytes(b"RIFF")
            messages = queue.Queue()
            observer_calls = {"emotion": 0, "cue": 0}

            def raise_emotion_error(expression):
                observer_calls["emotion"] += 1
                raise RuntimeError("emotion observer failed")

            def raise_cue_error(expression, path):
                observer_calls["cue"] += 1
                raise RuntimeError("cue observer failed")

            reply = CompanionStreamingReply(
                "sentence-id",
                messages,
                on_expression=raise_emotion_error,
                on_cue_enqueued=raise_cue_error,
                companion_config={"cue_files": {"sigh": "sigh.wav"}},
                cue_resource_root=directory,
            )

            reply.feed('{"emotion":"gentle","cue":"sigh"}\n你')
            reply.start()
            reply.feed("好")
            reply.finish()

            queued = list(messages.queue)
            self.assertEqual(
                1,
                sum(message.sentence_type == SentenceType.FIRST for message in queued),
            )
            self.assertEqual(
                1,
                sum(message.content_type == ContentType.FILE for message in queued),
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
            self.assertEqual({"emotion": 1, "cue": 1}, observer_calls)
            self.assertTrue(reply.started)
            self.assertTrue(reply.cue_enqueued)


if __name__ == "__main__":
    unittest.main()
