from core.companion.emotion_policy import EmotionPolicy
from core.companion.reply_protocol import CompanionReplyStreamParser
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


class CompanionStreamingReply:
    def __init__(
        self,
        sentence_id,
        output_queue,
        on_expression=None,
    ):
        self.sentence_id = sentence_id
        self.output_queue = output_queue
        self.on_expression = on_expression
        self.parser = CompanionReplyStreamParser()
        self.started = False
        self.text_parts = []

    @property
    def expression(self):
        return EmotionPolicy().resolve(self.parser.metadata)

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
