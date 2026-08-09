from dataclasses import replace

from core.companion.emotion_policy import EmotionPolicy
from core.companion.cue_policy import CuePolicy
from core.companion.reply_protocol import CompanionReplyStreamParser
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


class CompanionStreamingReply:
    def __init__(
        self,
        sentence_id,
        output_queue,
        on_expression=None,
        companion_config=None,
        on_cue_enqueued=None,
        user_input=None,
        cue_resource_root=None,
    ):
        self.sentence_id = sentence_id
        self.output_queue = output_queue
        self.on_expression = on_expression
        self.on_cue_enqueued = on_cue_enqueued
        self.user_input = user_input
        self.parser = CompanionReplyStreamParser()
        self.started = False
        self.text_parts = []
        self.cue_policy = CuePolicy(
            (companion_config or {}).get("cue_files", {}),
            resource_root=cue_resource_root,
        )
        self.cue_enqueued = False

    @property
    def expression(self):
        expression = EmotionPolicy().resolve(self.parser.metadata)
        cue = self.cue_policy.select(expression.cue, self.user_input)
        if not self.cue_policy.is_compatible(cue, expression.display_emotion):
            cue = None
        return replace(expression, cue=cue)

    def feed(self, chunk):
        for text in self.parser.feed(chunk):
            self._enqueue_text(text)

    def finish(self):
        for text in self.parser.finish():
            self._enqueue_text(text)
        self._ensure_started()
        self.output_queue.put(TTSMessageDTO(
            self.sentence_id,
            SentenceType.LAST,
            ContentType.ACTION,
            expression=self.expression,
        ))

    def start(self):
        self._ensure_started()

    def _ensure_started(self):
        if self.started:
            return
        expression = self.expression
        self.output_queue.put(TTSMessageDTO(
            self.sentence_id,
            SentenceType.FIRST,
            ContentType.ACTION,
            expression=expression,
        ))
        if self.on_expression is not None:
            self._notify(self.on_expression, expression)
        cue_path = self.cue_policy.resolve(expression.cue)
        if cue_path is not None and not self.cue_enqueued:
            self.output_queue.put(TTSMessageDTO(
                self.sentence_id,
                SentenceType.MIDDLE,
                ContentType.FILE,
                content_detail="",
                content_file=cue_path,
                expression=expression,
            ))
            self.cue_enqueued = True
            if self.on_cue_enqueued is not None:
                self._notify(self.on_cue_enqueued, expression, cue_path)
        self.started = True

    @staticmethod
    def _notify(callback, *args):
        try:
            callback(*args)
        except Exception:
            pass

    def _enqueue_text(self, text):
        if not text:
            return
        self._ensure_started()
        self.text_parts.append(text)
        self.output_queue.put(TTSMessageDTO(
            self.sentence_id,
            SentenceType.MIDDLE,
            ContentType.TEXT,
            content_detail=text,
            expression=self.expression,
        ))
