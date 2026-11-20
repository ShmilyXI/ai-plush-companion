from __future__ import annotations

import asyncio
import base64
import json
import threading
import time
import uuid
from datetime import datetime
from collections import deque
from collections.abc import Callable, Mapping
from typing import Any

from .protocol import (
    AudioTurnInput,
    ConversationEvent,
    RuntimeTokenClaims,
    TextTurnInput,
    WEB_REALTIME_PROTOCOL_VERSION,
)
from .protocol import MAX_AUDIO_OUTPUT_BYTES, MAX_OUTPUT_TEXT_LENGTH, has_words
from config.logger import setup_logging
from core.companion.proactive_planner import ProactivePlanner
from core.companion.reply_protocol import CompanionReplyStreamParser
from core.utils.prompt_manager import COMPANION_REPLY_CONTRACT

from .safety import (
    BLOCK,
    BLOCKED_INPUT_REPLY,
    CRISIS,
    CRISIS_INSTRUCTION,
    CRISIS_NOTICE,
    PLATFORM_SAFETY_POLICY,
    WITHDRAWN_REPLY,
    ContentSafety,
)
from .opening import (
    LOCAL_ZONE,
    OPENING_CONTEXT_MARK,
    gap_hours_setting,
    opening_instruction,
    should_open,
)
from .speech import SpeechStream
from .tool_calls import PublicToolCallAccumulator
from .tools import PublicConversationToolRuntime, PublicToolError


MAX_COMPLETED_TURNS = 100
# 语音合成服务声明的文件格式对应的类型。标错了类型，手机浏览器会拒绝播放。
_AUDIO_MIME_TYPES = {"mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg", "opus": "audio/opus"}
# 带给模型的会话内上下文：最多这么多轮，且总字数不超过上限。
MAX_CONTEXT_TURNS = 20
MAX_CONTEXT_CHARS = 8000


# 本地语音识别模型有几百兆到上千兆，加载一次要好几秒。它在两次识别之间不保留状态，
# 所以同一份配置在整个进程里只加载一次，所有会话共用；否则每来一个用户就多占一份内存、多等一次加载。
_SHARED_ASR: dict[str, Any] = {}
_SHARED_ASR_LOCK = threading.Lock()


def shared_asr_instance(model: Mapping[str, Any]) -> Any:
    from core.utils import asr

    config = dict(model)
    key = json.dumps(config, sort_keys=True, default=str, ensure_ascii=False)
    # 加载放在锁里：两个用户同时第一次说话时，后来的等先来的加载完，而不是各加载一份。
    with _SHARED_ASR_LOCK:
        cached = _SHARED_ASR.get(key)
        if cached is not None:
            return cached
        instance = asr.create_instance(str(config["type"]), config, True)
        # 云端的流式识别按连接保留状态，不能共用，每个会话各用各的。
        if getattr(getattr(instance, "interface_type", None), "name", None) == "LOCAL":
            _SHARED_ASR[key] = instance
        return instance


def _epoch_ms(value: Any) -> int | None:
    """manager-api 把长整数序列化成字符串，避免浏览器丢精度。这里统一还原成毫秒数。"""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


class _ReplyWithdrawn(Exception):
    """模型的回复里出现了不允许的内容，已经输出的部分要收回。"""

    def __init__(self, verdict):
        super().__init__(verdict.category or "blocked")
        self.verdict = verdict


# 角色最多带着多少条记忆进对话。和 manager-api 保存的上限一致。
MAX_MEMORY_ITEMS = 60
# 用户这句话太短（“嗯”“好的”）就不去提炼记忆，省一次模型调用。
MIN_MEMORY_SOURCE_LENGTH = 6
MAX_MEMORY_ITEM_LENGTH = 200

MEMORY_EXTRACTION_PROMPT = """你在帮一个陪伴角色记住关于用户的事。看用户刚说的话，挑出以后聊天还用得上的事实。

值得记的：用户的名字或称呼、身边重要的人和宠物、工作和学业、喜好和习惯、正在经历或计划的事、明确说过的愿望和顾虑。
不要记的：寒暄和一时的情绪、只和眼下这句话有关的细节、角色自己说的话、已经记得的事、猜测出来的事。
身份证号、银行卡号、密码、详细住址这类信息一律不记。

每条写成一句简短的陈述，以“用户”开头，例如“用户养了一只叫年糕的猫”。
只输出一个 JSON 数组，例如 ["用户下个月有一场面试"]。没有值得记的就输出 []。不要输出别的内容。"""


def parse_memory_items(raw: str, known: list[str]) -> list[str]:
    """从模型的输出里取出要记的事。格式不对、内容为空或已经记得的都丢掉。"""
    start, end = raw.find("["), raw.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        values = json.loads(raw[start:end + 1])
    except (TypeError, ValueError):
        return []
    if not isinstance(values, list):
        return []
    seen = {"".join(ch for ch in item if ch.isalnum()) for item in known}
    items: list[str] = []
    for value in values:
        if not isinstance(value, str):
            continue
        item = " ".join(value.split())[:MAX_MEMORY_ITEM_LENGTH]
        key = "".join(ch for ch in item if ch.isalnum())
        if not key or key in seen:
            continue
        seen.add(key)
        items.append(item)
        if len(items) >= 5:
            break
    return items


_logger = setup_logging()

# 主动找话题：用户连着几次没接话就不再找；两次之间至少隔多久；一句最多多少字。
MAX_UNANSWERED_NUDGES = 3
MIN_NUDGE_INTERVAL_SECONDS = 20
NUDGE_MAX_CHARS = 60

WEB_NUDGE_PROMPT = """这是语音通话，用户一阵子没说话了。他可能在想事情、在忙别的，也可能只是不知道说什么。
主动开口的目标是自然、克制地陪着，不是让通话一直热闹。
优先接上最近没聊完的话题，或者用户提到过的近况；没有合适的就说一句轻松的、不需要用户回答的话，或者选择安静。
不要连着问问题，不要催用户说话，不要重复自己刚才主动说过的话。"""


class PublicConversationSession:
    def __init__(
        self,
        claims: RuntimeTokenClaims,
        bundle: Mapping[str, Any],
        *,
        llm_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        asr_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        tts_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        history_loader: Callable[[int], Any] | None = None,
        history_writer: Callable[[dict[str, Any]], Any] | None = None,
        tool_runtime_factory: Callable[[Mapping[str, Any]], Any] | None = None,
        safety_reporter: Callable[[dict[str, Any]], Any] | None = None,
        memory_writer: Callable[[list[str]], Any] | None = None,
        speech_check: Callable[[bytes], bool] | None = None,
    ):
        self.claims = claims
        self.bundle = bundle
        self.runtime_models = bundle.get("runtime_models") or {}
        self._seen_requests: set[str] = set()
        self._cancelled_turns: set[str] = set()
        self._completed_turns: set[str] = set()
        self._active_turns: set[str] = set()
        self._cancelled_emitted: set[str] = set()
        self._turn_tasks: dict[str, asyncio.Task[Any]] = {}
        self._turn_requests: dict[str, str] = {}
        self._turn_segments: dict[str, str | None] = {}
        self._turn_events: dict[str, str | None] = {}
        self._sequence = 0
        self._llm_factory = llm_factory or self._create_llm
        self._asr_factory = asr_factory or self._create_asr
        self._tts_factory = tts_factory or self._create_tts
        self._memory_factory = self._create_memory
        self._history_loader = history_loader
        self._history_writer = history_writer
        self._safety_reporter = safety_reporter
        # 角色记得的关于用户的事：开始对话时取回来放进提示词，每轮之后把新的记下来。
        self._memory_writer = memory_writer
        # 交给语音识别之前先看看有没有人在说话。没有设置时不检查（测试和第三方接入用）。
        self._speech_check = speech_check
        self._remembered: list[str] = []
        self._memory_tasks: set[asyncio.Task[Any]] = set()
        self._tool_runtime_factory = tool_runtime_factory or PublicConversationToolRuntime
        self._llm = None
        self._asr = None
        self._tts = None
        # 正在合成语音的轮次。每一轮一条流水线，回复写到哪句就合成到哪句。
        self._speech: dict[str, SpeechStream] = {}
        # 之前的对话记录有没有成功取回来。没取回来时不知道是不是第一次见面，就不主动开口。
        self._thread_restored = False
        self._opening_requested = False
        # 说完了的开场白。它不算用户的一轮，所以不放进 _completed_turns，但打断它时要当作已经结束的轮次。
        self._finished_openings: set[str] = set()
        # 主动找话题：用户没接话的情况下已经主动说了几次、上一次是什么时候、说过什么。
        self._unanswered_nudges = 0
        self._last_nudge_at = 0.0
        self._nudge_lines: list[str] = []
        # 每开始一轮用户的话就加一。主动开口前先想一会儿，想完发现用户已经开口了就不说了。
        self._user_turn_epoch = 0
        self._memory = None
        self._tool_runtime = None
        self._history: deque[dict[str, Any]] = deque(maxlen=50)
        self._turn_modes: dict[str, str] = {}
        self._turn_started_at: dict[str, float] = {}
        self._turn_first_text_at: dict[str, float] = {}
        # 这个会话最多还能进行多少轮。manager-api 按用户今天剩余的次数给出，不超过单个会话的上限。
        self._turn_budget = self._configured_turn_budget()
        self._daily_quota_exhausted = False
        self._safety = ContentSafety((self.bundle.get("config") or {}).get("safetyBlockedTerms"))

    def _create_llm(self, model: Mapping[str, Any]) -> Any:
        from core.utils import llm

        return llm.create_instance(str(model["type"]), dict(model))

    def _create_asr(self, model: Mapping[str, Any]) -> Any:
        return shared_asr_instance(model)

    def _create_tts(self, model: Mapping[str, Any]) -> Any:
        from core.utils import tts

        return tts.create_instance(str(model["type"]), dict(model), True)

    def _create_memory(self, model: Mapping[str, Any]) -> Any:
        from core.utils import memory

        memory_type = model.get("type") or model.get("id")
        if not memory_type:
            raise RuntimeError("Memory provider type is missing")
        return memory.create_instance(
            str(memory_type), dict(model), (self.bundle.get("config") or {}).get("summaryMemory")
        )

    def _event(self, event_type: str, sequence: int, turn_id: str | None, details: Mapping[str, Any] | None = None,
               *, request_id: str | None = None, segment_id: str | None = None,
               event_id: str | None = None) -> ConversationEvent:
        if turn_id is not None:
            request_id = request_id or self._turn_requests.get(turn_id)
            segment_id = segment_id or self._turn_segments.get(turn_id)
            event_id = event_id or self._turn_events.get(turn_id)
        return ConversationEvent(
            event_type=event_type,
            conversation_id=self.claims.conversation_id,
            turn_id=turn_id,
            sequence=sequence,
            occurred_at=int(time.time() * 1000),
            details=details or {},
            request_id=request_id,
            segment_id=segment_id,
            event_id=event_id,
        )

    def _next(self, events: list[ConversationEvent], event_type: str, turn_id: str | None,
              details: Mapping[str, Any] | None = None, *, request_id: str | None = None,
              segment_id: str | None = None, event_id: str | None = None) -> None:
        self._sequence += 1
        events.append(self._event(event_type, self._sequence, turn_id, details,
                                  request_id=request_id, segment_id=segment_id, event_id=event_id))

    def _duplicate(self, request_id: str | None = None, segment_id: str | None = None,
                   event_id: str | None = None) -> list[ConversationEvent]:
        events: list[ConversationEvent] = []
        details = {"code": "duplicate_request", "message": "request_id 已处理", "retryable": False}
        self._next(events, "error", None, details, request_id=request_id,
                   segment_id=segment_id, event_id=event_id)
        return events

    def _configured_turn_budget(self) -> int:
        value = (self.bundle.get("config") or {}).get("turnBudget")
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return MAX_COMPLETED_TURNS
        return max(0, min(int(value), MAX_COMPLETED_TURNS))

    def _expired(self) -> bool:
        return int(time.time()) >= self.claims.expires_at

    def _session_expired(self) -> list[ConversationEvent]:
        return [self.expired()]

    def expired(self, reason: str = "runtime_token_expired") -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "session.expired", None, {"reason": reason})
        return events[0]

    def ready(self) -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "session.ready", None, {
            "agent_version": self.claims.agent_version,
            "protocol_version": WEB_REALTIME_PROTOCOL_VERSION,
            "input_modes": list(self.claims.input_modes),
            "output_modes": list(self.claims.output_modes),
            "expires_at": self.claims.expires_at,
            "supports": {"continuous_audio": "audio" in self.claims.input_modes,
                          "interruption": True, "heartbeat": True},
        })
        return events[0]

    def stream_ready(self, sample_rate: int = 16000, channels: int = 1) -> ConversationEvent:
        events: list[ConversationEvent] = []
        self._next(events, "stream.ready", None, {
            "sample_rate": sample_rate, "channels": channels, "format": "pcm_s16le",
        })
        return events[0]

    def _quota_error(self) -> ConversationEvent | None:
        """这一轮还能不能开始。分两种上限：用户今天的对话次数，和单个会话的轮数。"""
        daily_limited = self._turn_budget < MAX_COMPLETED_TURNS
        if self._daily_quota_exhausted or (daily_limited and len(self._completed_turns) >= self._turn_budget):
            return self._event("error", self._next_error_sequence(), None, {
                "code": "daily_quota_exceeded",
                "message": "今天的对话次数已经用完了，明天再来吧",
                "retryable": False,
            })
        if len(self._completed_turns) >= MAX_COMPLETED_TURNS:
            return self._event("error", self._next_error_sequence(), None, {
                "code": "turn_quota_exceeded", "message": "会话轮次已达到上限", "retryable": False})
        return None

    def _start(self, request_id: str, input_mode: str, segment_id: str | None = None,
               event_id: str | None = None) -> tuple[str, list[ConversationEvent]]:
        if request_id in self._seen_requests:
            return "", self._duplicate(request_id, segment_id, event_id)
        self._seen_requests.add(request_id)
        turn_id = uuid.uuid4().hex
        self._turn_modes[turn_id] = input_mode
        if input_mode in ("text", "audio"):
            # 用户说话了：主动找话题重新计数。
            self._user_turn_epoch += 1
            self._unanswered_nudges = 0
        self._turn_started_at[turn_id] = time.monotonic()
        self._active_turns.add(turn_id)
        self._turn_requests[turn_id] = request_id
        self._turn_segments[turn_id] = segment_id
        self._turn_events[turn_id] = event_id
        events: list[ConversationEvent] = []
        self._next(events, "turn.started", turn_id, {"request_id": request_id, "input_mode": input_mode})
        return turn_id, events

    def _dialogue(self, text: str, memory_text: str | None = None,
                  skill_prompt: str | None = None, images: tuple[str, ...] = (),
                  crisis: bool = False) -> list[dict[str, Any]]:
        config = self.bundle.get("config") or {}
        prompt = str(config.get("systemPrompt") or "")
        role_prompt = str(config.get("rolePrompt") or "")
        if role_prompt:
            prompt = f"{prompt}\n\n{role_prompt}".strip()
        # 角色自己的名字、对用户的称呼，以及它记得的关于用户的事。
        prompt = f"{prompt}{self._identity_and_memory(config, prompt)}".strip()
        if prompt:
            # 和设备端一样把情绪标记的确切格式告诉模型。角色提示词只说了“使用情绪协议”，
            # 不给格式的话模型每次写法都不一样，过滤不干净就会被念出来。
            prompt = f"{prompt}{COMPANION_REPLY_CONTRACT}".strip()
        # 平台守则对所有角色生效，放在最后，优先于角色设定。
        prompt = f"{prompt}{PLATFORM_SAFETY_POLICY}".strip()
        if crisis:
            prompt = f"{prompt}{CRISIS_INSTRUCTION}".strip()
        if memory_text and memory_text.strip():
            prompt = f"{prompt}\n\n<memory>\n{memory_text.strip()}\n</memory>".strip()
        if skill_prompt and skill_prompt.strip():
            prompt = f"{prompt}\n\n<skill_execution>\n{skill_prompt.strip()}\n</skill_execution>".strip()
        content: Any = text
        if images:
            content = ([{"type": "text", "text": text}] if text.strip() else [])
            content.extend({"type": "image_url", "image_url": {"url": image}} for image in images)
        messages = self._recent_turns() + [{"role": "user", "content": content}]
        return ([{"role": "system", "content": prompt}] if prompt else []) + messages

    def seed_memories(self, items: list[str]) -> None:
        """放进之前记下的关于用户的事。只在会话刚打开时调用。"""
        self._remembered = [item.strip() for item in items if isinstance(item, str) and item.strip()][-MAX_MEMORY_ITEMS:]

    def _identity_and_memory(self, config: Mapping[str, Any], prompt: str) -> str:
        lines: list[str] = []
        name = str(config.get("profileName") or "").strip()
        if name and name not in prompt:
            # 用户自己建的角色，提示词里不一定写了它叫什么。
            lines.append(f"你的名字是{name}。")
        address = str(config.get("userAddress") or "").strip()
        if address:
            lines.append(f"用户希望你这样称呼对方：{address}。")
        text = ("\n\n" + "\n".join(lines)) if lines else ""
        if self._remembered:
            facts = "\n".join(f"- {item}" for item in self._remembered)
            text += ("\n\n你记得关于用户的这些事。聊到相关的话题时自然地用上，"
                     "不要一条条复述，也不要说“根据记录”这类话：\n" + facts)
        return text

    def _schedule_remember(self, text: str, reply: str) -> None:
        """这一轮说完后，在后台看看有没有值得记住的事。不影响回复和语音。"""
        if self._memory_writer is None or not has_words(text) or len(text.strip()) < MIN_MEMORY_SOURCE_LENGTH:
            return
        task = asyncio.ensure_future(self._remember(text, reply))
        self._memory_tasks.add(task)
        task.add_done_callback(self._memory_tasks.discard)

    async def _remember(self, text: str, reply: str) -> None:
        try:
            if self._llm is None:
                return
            known = "\n".join(f"- {item}" for item in self._remembered) or "（还没有）"
            dialogue = [
                {"role": "system", "content": MEMORY_EXTRACTION_PROMPT},
                {"role": "user", "content": f"已经记得的：\n{known}\n\n用户刚说的话：{text[:1000]}\n角色的回复：{reply[:600]}"},
            ]
            llm = self._llm
            conversation_id = self.claims.conversation_id

            def produce() -> str:
                return "".join(chunk for chunk in llm.response(conversation_id, dialogue) if isinstance(chunk, str))

            items = parse_memory_items(await asyncio.to_thread(produce), self._remembered)
            if not items:
                return
            result = self._memory_writer(items)
            if hasattr(result, "__await__"):
                await result
            self._remembered = (self._remembered + items)[-MAX_MEMORY_ITEMS:]
        except Exception:
            # 记不下来不影响对话。
            return

    def seed_history(self, items: list[dict[str, Any]]) -> None:
        """放入这个用户和角色在之前的会话里聊过的内容，作为本次会话的起始上下文。

        只在会话刚建立、还没有任何一轮时生效；这些轮次不计入本次会话的轮数上限，也不会再写回存储。
        """
        self._thread_restored = True
        if self._history or self._completed_turns:
            return
        for item in items:
            if not isinstance(item, Mapping):
                continue
            text = str(item.get("text") or "").strip()
            reply = str(item.get("reply") or "").strip()
            # 开场白是角色主动说的，没有用户那句话。其他缺了一半的记录不要。
            opening = not text and item.get("opening") is True
            if reply and (text or opening):
                self._history.append({
                    "turn_id": str(item.get("turn_id") or ""),
                    "text": text[:MAX_OUTPUT_TEXT_LENGTH],
                    "reply": reply[:MAX_OUTPUT_TEXT_LENGTH],
                    "occurred_at": _epoch_ms(item.get("occurred_at")),
                    **({"opening": True} if opening else {}),
                })

    def _recent_turns(self) -> list[dict[str, Any]]:
        """本次会话里已经完成的对话，按时间顺序给模型当上下文。

        没有这段上下文，模型每一轮都只看到用户当前这一句话，上一句刚说过的事它也不知道。
        只取最近若干轮并限制总字数，避免长对话把提示词撑得又慢又贵；更早的内容由记忆负责。
        """
        selected: list[dict[str, Any]] = []
        budget = MAX_CONTEXT_CHARS
        for item in reversed(self._history):
            # 开场白没有用户那句话，用一句固定的说明占住用户的位置。
            text = str(item.get("text") or "").strip()
            reply = str(item.get("reply") or "").strip()
            if not text and item.get("opening") is True:
                text = OPENING_CONTEXT_MARK
            if not text or not reply:
                continue
            cost = len(text) + len(reply)
            if len(selected) >= MAX_CONTEXT_TURNS or cost > budget:
                break
            budget -= cost
            selected.append({"text": text, "reply": reply})
        messages: list[dict[str, Any]] = []
        for item in reversed(selected):
            messages.append({"role": "user", "content": item["text"]})
            messages.append({"role": "assistant", "content": item["reply"]})
        return messages

    async def _run_text(self, turn_id: str, text: str, events: list[ConversationEvent], emit=None,
                        images: tuple[str, ...] = ()) -> list[ConversationEvent]:
        try:
            return await self._run_text_turn(turn_id, text, events, emit, images)
        finally:
            # 不管这一轮是正常结束、出错还是被取消，都不能留下还在后台合成语音的任务。
            await self._drop_speech(turn_id)

    async def _run_text_turn(self, turn_id: str, text: str, events: list[ConversationEvent], emit,
                             images: tuple[str, ...]) -> list[ConversationEvent]:
        if turn_id in self._cancelled_turns:
            if turn_id not in self._cancelled_emitted:
                self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
                await self._emit_last(events, emit)
            return events
        verdict = self._safety.check_input(text)
        if verdict.action == BLOCK:
            # 明显违规的输入不交给模型，也不进入对话记录和记忆，只回一句固定的话。
            await self._report_safety(turn_id, "input", verdict)
            visible = await self._emit_delta(turn_id, "", BLOCKED_INPUT_REPLY, events, emit)
            if not await self._speak(turn_id):
                return events
            self._completed_turns.add(turn_id)
            self._discard_turn_metrics(turn_id)
            self._next(events, "turn.completed", turn_id, {"text": visible})
            await self._emit_last(events, emit)
            return events
        crisis = verdict.action == CRISIS
        if crisis:
            await self._report_safety(turn_id, "input", verdict)
        model = self.runtime_models.get("LLM") or {}
        if self._llm is None:
            self._llm = self._llm_factory(model)
        memory_text = await self._query_memory(text)
        tool_runtime, tool_turn = await self._select_tool_turn(text)
        skill_prompt = tool_turn.skill.execution_prompt if tool_turn is not None and tool_turn.skill else None
        dialogue = self._dialogue(text, memory_text, skill_prompt, images, crisis)
        schemas = tool_runtime.schemas(tool_turn) if tool_runtime is not None and tool_turn is not None else []
        try:
            if schemas:
                visible = await self._run_with_tools(
                    turn_id, dialogue, events, emit, tool_runtime, tool_turn, schemas
                )
            else:
                visible = await self._emit_visible_stream(turn_id, dialogue, events, emit)
        except _ReplyWithdrawn as withdrawn:
            # 回复说到一半出现了不该有的内容：告诉客户端收回已经显示的部分，换成固定的话。
            # 前面几句可能已经合成并在播放了：先停掉还没发出去的语音，客户端收到收回事件后会停止播放。
            await self._drop_speech(turn_id)
            await self._report_safety(turn_id, "output", withdrawn.verdict)
            visible = WITHDRAWN_REPLY
            self._next(events, "reply.withdrawn", turn_id, {"text": visible})
            await self._emit_last(events, emit)
            self._feed_speech(turn_id, visible, events, emit)
        if not visible:
            if turn_id in self._cancelled_turns:
                return events
            raise RuntimeError("LLM 未返回文本")
        if turn_id in self._cancelled_turns:
            return events
        if crisis and CRISIS_NOTICE not in visible:
            # 求助信息由系统补上，不依赖模型记得写，也不会被模型写错号码。
            visible = await self._emit_delta(turn_id, visible, "\n" + CRISIS_NOTICE, events, emit)
        # 回复的文字到这里已经完整显示给用户了。从现在起这一轮就要记下来，不等语音读完：
        # 用户可能在听到一半时关掉页面、开口打断，语音合成也可能失败，这些都不该让角色忘掉刚说过的话。
        # 记录和朗读同时进行，所以不会拖慢最后一句的语音。
        record = asyncio.ensure_future(self._record_turn(turn_id, text, visible))
        try:
            spoken = await self._speak(turn_id)
        finally:
            await asyncio.shield(record)
        if not spoken:
            return events
        self._completed_turns.add(turn_id)
        self._next(events, "turn.completed", turn_id, {"text": visible})
        await self._emit_last(events, emit)
        return events

    async def _record_turn(self, turn_id: str, text: str, visible: str) -> None:
        """把说完的一轮记进会话内的上下文、记忆和服务端的对话记录。"""
        history_item = {"turn_id": turn_id, "text": text[:MAX_OUTPUT_TEXT_LENGTH],
                        "reply": visible[:MAX_OUTPUT_TEXT_LENGTH], "occurred_at": int(time.time() * 1000)}
        # 用量随这一轮一起上报，但不放进会话内的历史里。
        usage = self._turn_usage(turn_id, text, visible)
        self._history.append(history_item)
        await self._save_memory(text, visible)
        await self._write_history({**history_item, "usage": usage})
        self._schedule_remember(text, visible)

    async def _speak(self, turn_id: str) -> bool:
        """回复写完了：把剩下的话合成完，等所有语音都发给客户端。返回 False 表示这一轮期间被取消了。"""
        stream = self._speech.pop(turn_id, None)
        if stream is not None:
            await stream.finish()
        return turn_id not in self._cancelled_turns

    def _feed_speech(self, turn_id: str, text: str, events: list[ConversationEvent], emit) -> None:
        """把刚显示出来的文字交给语音流水线。写完一句合成一句，不等整段回复结束。"""
        if "audio" not in self.claims.output_modes or not text:
            return
        stream = self._speech.get(turn_id)
        if stream is None:
            async def deliver(index: int, segment: str, audio: bytes, mime_type: str) -> bool:
                if turn_id in self._cancelled_turns:
                    return False
                self._next(events, "tts.audio", turn_id, {
                    "mime_type": mime_type,
                    "data": base64.b64encode(audio).decode("ascii"),
                    "text": segment,
                    "segment_index": index,
                })
                await self._emit_last(events, emit)
                return True

            stream = self._speech[turn_id] = SpeechStream(self._synthesize, deliver)
        stream.feed(text)

    async def _drop_speech(self, turn_id: str) -> None:
        stream = self._speech.pop(turn_id, None)
        if stream is not None:
            await stream.abort()

    async def _synthesize(self, text: str) -> tuple[bytes, str]:
        model = self.runtime_models.get("TTS") or {}
        if self._tts is None:
            self._tts = self._tts_factory(model)
        text = text.strip()
        if hasattr(self._tts, "to_playground_wav"):
            audio = await asyncio.to_thread(self._tts.to_playground_wav, text)
            mime_type = "audio/wav"
        else:
            audio = await self._tts.text_to_speak(text, None)
            mime_type = _AUDIO_MIME_TYPES.get(str(getattr(self._tts, "audio_file_type", "")).lower(), "audio/opus")
        if not isinstance(audio, bytes) or not audio:
            raise RuntimeError("TTS 未返回音频")
        if len(audio) > MAX_AUDIO_OUTPUT_BYTES:
            raise RuntimeError("TTS 音频超出大小限制")
        return audio, mime_type

    def _discard_turn_metrics(self, turn_id: str) -> None:
        self._turn_modes.pop(turn_id, None)
        self._turn_started_at.pop(turn_id, None)
        self._turn_first_text_at.pop(turn_id, None)

    async def _report_safety(self, turn_id: str, direction: str, verdict) -> None:
        """留一条安全事件供复核。只记类别和处理方式，不记对话内容。上报失败不影响对话。"""
        if self._safety_reporter is None:
            return
        try:
            result = self._safety_reporter({
                "turn_id": turn_id, "direction": direction,
                "action": verdict.action, "category": verdict.category,
            })
            if hasattr(result, "__await__"):
                await result
        except Exception:
            return

    async def _emit_visible_stream(self, turn_id, dialogue, events, emit) -> str:
        visible = ""
        # 角色提示词要求模型在回复开头带一个情绪标记，供设备驱动表情。
        # 这个标记不是说给用户听的话，不能出现在文字和语音里。
        parser = CompanionReplyStreamParser()
        async for chunk in self._stream_llm(dialogue):
            if await self._cancelled(turn_id, events, emit):
                return ""
            for value in parser.feed(str(chunk or "")):
                visible = await self._emit_delta(turn_id, visible, value, events, emit)
        for value in parser.finish():
            visible = await self._emit_delta(turn_id, visible, value, events, emit)
        return visible

    async def _emit_delta(self, turn_id, visible: str, value: str, events, emit) -> str:
        if not value:
            return visible
        self._turn_first_text_at.setdefault(turn_id, time.monotonic())
        visible += value
        if len(visible) > MAX_OUTPUT_TEXT_LENGTH:
            raise RuntimeError("LLM 输出超出大小限制")
        # 每多一段就把到目前为止的回复整体检查一遍，违禁内容跨了两段也能发现。
        verdict = self._safety.check_output(visible)
        if verdict.action == BLOCK:
            raise _ReplyWithdrawn(verdict)
        self._next(events, "llm.delta", turn_id, {"text": value})
        await self._emit_last(events, emit)
        self._feed_speech(turn_id, value, events, emit)
        return visible

    async def _run_with_tools(self, turn_id, dialogue, events, emit, runtime, tool_turn, schemas) -> str:
        if not callable(getattr(self._llm, "response_with_functions", None)):
            name = schemas[0].get("function", {}).get("name", "readonly_tool")
            self._next(events, "tool.failed", turn_id, {
                "name": name,
                "code": "provider_unsupported",
                "message": "当前模型不支持实时工具调用",
            })
            await self._emit_last(events, emit)
            message = "当前模型不支持实时工具调用，暂时无法获取实时天气或新闻。"
            self._next(events, "llm.delta", turn_id, {"text": message})
            await self._emit_last(events, emit)
            self._feed_speech(turn_id, message, events, emit)
            return message

        for round_index in range(2):
            buffered: list[str] = []
            accumulator = PublicToolCallAccumulator()
            async for content, fragments in self._stream_llm_functions(dialogue, schemas):
                if await self._cancelled(turn_id, events, emit):
                    return ""
                if content:
                    buffered.append(str(content))
                if fragments:
                    accumulator.add(fragments)
            calls = accumulator.finish()
            if not calls:
                visible = ""
                parser = CompanionReplyStreamParser()
                for chunk in buffered:
                    for value in parser.feed(chunk):
                        visible = await self._emit_delta(turn_id, visible, value, events, emit)
                for value in parser.finish():
                    visible = await self._emit_delta(turn_id, visible, value, events, emit)
                return visible
            if round_index >= 1:
                raise RuntimeError("工具调用轮数超出限制")
            dialogue.append({
                "role": "assistant",
                "content": "".join(buffered),
                "tool_calls": [call.assistant_value() for call in calls],
            })
            for call in calls:
                if await self._cancelled(turn_id, events, emit):
                    return ""
                self._next(events, "tool.started", turn_id, {"name": call.name})
                await self._emit_last(events, emit)
                try:
                    result = await runtime.execute(tool_turn, call.name, call.arguments)
                    self._next(events, "tool.completed", turn_id, {
                        "name": result.name,
                        "duration_ms": result.duration_ms,
                    })
                    tool_content = result.content
                except PublicToolError as error:
                    message = self._public_tool_failure_message(error.code)
                    self._next(events, "tool.failed", turn_id, {
                        "name": call.name,
                        "code": error.code,
                        "message": message,
                    })
                    tool_content = (
                        f"实时工具调用失败：{message}。请明确告诉用户当前数据不可用，"
                        "不要猜测或编造实时天气、新闻。"
                    )
                await self._emit_last(events, emit)
                dialogue.append({
                    "role": "tool",
                    "tool_call_id": call.id,
                    "name": call.name,
                    "content": tool_content,
                })
        raise RuntimeError("工具调用未产生最终回复")

    async def _select_tool_turn(self, text):
        try:
            if self._tool_runtime is None:
                self._tool_runtime = self._tool_runtime_factory(self.bundle)
            return self._tool_runtime, await self._tool_runtime.select(text)
        except Exception:
            self._tool_runtime = None
            # A test harness or optional loader may temporarily replace the
            # manifest module. Skill routing itself remains deterministic and
            # must not disappear with the plugin executor catalog.
            try:
                fallback = PublicConversationToolRuntime(self.bundle, plugin_registry={})
                self._tool_runtime = fallback
                return fallback, await fallback.select(text)
            except Exception:
                return None, None

    async def _cancelled(self, turn_id, events, emit) -> bool:
        if turn_id not in self._cancelled_turns:
            return False
        if turn_id not in self._cancelled_emitted:
            self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
            await self._emit_last(events, emit)
        return True

    @staticmethod
    def _public_tool_failure_message(code: str) -> str:
        return {
            "timeout": "实时查询超时，请稍后再试",
            "invalid_arguments": "查询条件无效，请换一种说法",
            "not_allowed": "当前角色未授权此工具",
            "result_too_large": "查询结果过大，暂时无法处理",
        }.get(code, "实时查询暂时不可用")

    async def _stream_llm(self, dialogue):
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue()
        sentinel = object()

        def produce() -> None:
            try:
                for chunk in self._llm.response(self.claims.conversation_id, dialogue):
                    loop.call_soon_threadsafe(queue.put_nowait, ("chunk", chunk))
            except Exception as error:
                loop.call_soon_threadsafe(queue.put_nowait, ("error", error))
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, ("done", sentinel))

        worker = asyncio.create_task(asyncio.to_thread(produce))
        try:
            while True:
                kind, value = await queue.get()
                if kind == "chunk":
                    yield value
                elif kind == "error":
                    raise value
                else:
                    break
        finally:
            if not worker.done():
                worker.cancel()

    async def _stream_llm_functions(self, dialogue, schemas):
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue()
        sentinel = object()

        def produce() -> None:
            try:
                for value in self._llm.response_with_functions(
                    self.claims.conversation_id, dialogue, functions=schemas
                ):
                    loop.call_soon_threadsafe(queue.put_nowait, ("chunk", value))
            except Exception as error:
                loop.call_soon_threadsafe(queue.put_nowait, ("error", error))
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, ("done", sentinel))

        worker = asyncio.create_task(asyncio.to_thread(produce))
        try:
            while True:
                kind, value = await queue.get()
                if kind == "chunk":
                    if isinstance(value, tuple) and len(value) == 2:
                        yield value
                    elif isinstance(value, dict):
                        yield value.get("content"), value.get("tool_calls")
                    else:
                        raise RuntimeError("LLM 工具响应格式无效")
                elif kind == "error":
                    raise value
                else:
                    break
        finally:
            if not worker.done():
                worker.cancel()

    async def _emit_last(self, events, emit) -> None:
        if emit is not None and events:
            await emit(events[-1])

    async def _query_memory(self, text: str) -> str | None:
        try:
            memory_config = self.bundle.get("config") or {}
            if memory_config.get("memoryEnabled") is False or memory_config.get("memory_enabled") is False:
                return None
            if self._memory is None and "Memory" in self.runtime_models:
                self._memory = self._memory_factory(self.runtime_models["Memory"])
                initializer = getattr(self._memory, "init_memory", None)
                if callable(initializer):
                    initializer(
                    memory_namespace=str(
                        (self.bundle.get("profile_memory_namespace")
                         or self.bundle.get("profileMemoryNamespace")
                         or (self.bundle.get("config") or {}).get(
                             "profileMemoryNamespace",
                             (self.bundle.get("config") or {}).get(
                                 "memoryNamespace", self.claims.conversation_id)))
                    ),
                        llm=self._llm,
                        summary_memory=(self.bundle.get("config") or {}).get("summaryMemory"),
                        save_to_file=False,
                        source_metadata={
                            "source_conversation_id": self.claims.conversation_id,
                            "source_subject": self.claims.subject,
                            "source_agent_id": self.claims.agent_id,
                        },
                    )
            if self._memory is None:
                return None
            result = await self._memory.query_memory(text)
            return str(result or "")
        except Exception:
            self._memory = None
            return None

    async def _save_memory(self, text: str, reply: str) -> None:
        memory_config = self.bundle.get("config") or {}
        if (
            self._memory is None
            or memory_config.get("memoryEnabled") is False
            or memory_config.get("memory_enabled") is False
        ):
            return
        try:
            await self._memory.save_memory(
                [{"role": "user", "content": text}, {"role": "assistant", "content": reply}],
                session_id=self.claims.conversation_id,
            )
        except Exception:
            self._memory = None

    async def handle_text(self, item: TextTurnInput) -> list[ConversationEvent]:
        turn_id, events = self.begin_text(item)
        if not turn_id:
            return events
        return await self.finish_text(turn_id, item.text, events, images=item.images)

    def begin_text(self, item: TextTurnInput) -> tuple[str, list[ConversationEvent]]:
        if self._expired():
            return "", self._session_expired()
        quota_error = self._quota_error()
        if quota_error is not None:
            return "", [quota_error]
        if "text" not in self.claims.input_modes and not item.images:
            return "", [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "文本输入未授权", "retryable": False})]
        if item.images and "image" not in self.claims.input_modes:
            return "", [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "图片输入未授权", "retryable": False})]
        return self._start(item.request_id, "text", item.segment_id, item.event_id)

    async def finish_text(self, turn_id: str, text: str, events: list[ConversationEvent], emit=None,
                          images: tuple[str, ...] = ()) -> list[ConversationEvent]:
        if not turn_id:
            return events
        try:
            return await self._run_text(turn_id, text, events, emit, images)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            await self._emit_last(events, emit)
            return events
        finally:
            self._active_turns.discard(turn_id)

    def begin_opening(self, request_id: str) -> tuple[str, list[ConversationEvent]]:
        """用户刚打开页面时，客户端请求一次开场白。

        不该开口时什么都不返回，客户端不需要处理。一个会话只理会第一次请求。
        """
        if self._opening_requested or not isinstance(request_id, str) or not request_id.strip():
            return "", []
        self._opening_requested = True
        if self._expired() or self._quota_error() is not None:
            return "", []
        # 已经聊起来了，或者历史没取回来（不知道是不是第一次见面），都不开口。
        if not self._thread_restored or self._completed_turns or self._active_turns:
            return "", []
        last = self._history[-1] if self._history else None
        gap_hours = gap_hours_setting(self.bundle.get("config") or {})
        if not should_open(last.get("occurred_at") if last else None, last is not None, gap_hours, time.time() * 1000):
            return "", []
        return self._start(request_id, "opening")

    async def finish_opening(self, turn_id: str, events: list[ConversationEvent], emit=None) -> list[ConversationEvent]:
        if not turn_id:
            return events
        try:
            return await self._run_opening(turn_id, events, emit)
        except Exception:
            # 开场白是角色主动说的，用户没有在等它。失败了就安静地结束，不给用户看一条错误。
            if turn_id not in self._cancelled_turns:
                self._next(events, "turn.cancelled", turn_id, {"reason": "opening_failed"})
                await self._emit_last(events, emit)
            return events
        finally:
            self._active_turns.discard(turn_id)
            self._discard_turn_metrics(turn_id)
            await self._drop_speech(turn_id)

    async def _run_opening(self, turn_id: str, events: list[ConversationEvent], emit) -> list[ConversationEvent]:
        model = self.runtime_models.get("LLM") or {}
        if self._llm is None:
            self._llm = self._llm_factory(model)
        last = self._history[-1] if self._history else None
        instruction = opening_instruction(last.get("occurred_at") if last else None, datetime.now(LOCAL_ZONE))
        try:
            visible = await self._emit_visible_stream(turn_id, self._dialogue(instruction), events, emit)
        except _ReplyWithdrawn as withdrawn:
            # 没人问的话不需要换一句话来替代，直接把已经显示的收回。
            await self._drop_speech(turn_id)
            await self._report_safety(turn_id, "output", withdrawn.verdict)
            self._next(events, "reply.withdrawn", turn_id, {"text": ""})
            await self._emit_last(events, emit)
            raise
        if turn_id in self._cancelled_turns:
            return events
        if not visible.strip():
            raise RuntimeError("LLM 未返回文本")
        if not await self._speak(turn_id):
            return events
        # 开场白进对话记录，这样下一轮模型知道自己说过什么，用户刷新页面也不会再听一遍。
        # 它不算用户的一轮：不计入本次会话的轮数，也不占每日次数。
        item = {"turn_id": turn_id, "text": "", "reply": visible[:MAX_OUTPUT_TEXT_LENGTH],
                "occurred_at": int(time.time() * 1000), "opening": True}
        self._history.append(item)
        self._finished_openings.add(turn_id)
        await self._write_history({**item, "usage": self._turn_usage(turn_id, "", visible)})
        self._next(events, "turn.completed", turn_id, {"text": visible, "opening": True})
        await self._emit_last(events, emit)
        return events

    def nudge_allowed(self) -> bool:
        """现在能不能考虑主动开口：只给登录用户、不在别的一轮中间、没超过次数，并且离上一次够久。"""
        config = self.bundle.get("config") or {}
        if not config.get("historyThread") or self._expired() or self._quota_error() is not None:
            return False
        if self._active_turns or self._unanswered_nudges >= MAX_UNANSWERED_NUDGES:
            return False
        return time.monotonic() - self._last_nudge_at >= MIN_NUDGE_INTERVAL_SECONDS

    async def nudge(self, request_id: str, idle_seconds: Any, events: list[ConversationEvent],
                    emit=None) -> list[ConversationEvent]:
        """通话里用户一阵子没说话：想一想要不要主动说一句。

        先让模型判断有没有自然的切入点，没有就什么都不发，用户那边不会看到任何变化。
        有的话才开始一轮，把这句话像回复一样显示和读出来。它不算用户的一轮，不占每日次数。
        """
        if not isinstance(request_id, str) or not request_id.strip() or request_id in self._seen_requests:
            return events
        if not self.nudge_allowed():
            return events
        self._last_nudge_at = time.monotonic()
        epoch = self._user_turn_epoch
        try:
            if self._llm is None:
                self._llm = self._llm_factory(self.runtime_models.get("LLM") or {})
            config = self.bundle.get("config") or {}
            role = "\n".join(part for part in (
                f"你扮演的角色叫{str(config.get('profileName') or '').strip()}。" if config.get("profileName") else "",
                str(config.get("rolePrompt") or "").strip()[:600],
                f"用户希望被称呼为：{str(config.get('userAddress')).strip()}。" if config.get("userAddress") else "",
                "现在是语音通话，你说的话会被直接读出来，要像朋友随口说话，一两句就够。",
            ) if part)
            planner = ProactivePlanner(self._llm, global_prompt=str(config.get("proactivePlannerPrompt") or WEB_NUDGE_PROMPT),
                                       agent_guidance=role, max_chars=NUDGE_MAX_CHARS)
            recent: list[dict[str, str]] = []
            for item in list(self._history)[-12:]:
                if item.get("text"):
                    recent.append({"role": "user", "content": str(item["text"])})
                recent.append({"role": "assistant", "content": str(item.get("reply") or "")})
            memories = [{"id": str(index), "content": item} for index, item in enumerate(self._remembered[-5:])]
            try:
                idle = max(0, int(float(idle_seconds)))
            except (TypeError, ValueError):
                idle = 0
            plan = await asyncio.to_thread(planner.plan, recent, self._nudge_lines[-3:], memories, idle)
        except Exception:
            # 想不出来就不说，用户那边什么都不会看到。
            return events
        # 只记决定和原因，不记内容。用来看主动找话题实际说了多少、为什么不说。
        _logger.info(f"主动找话题: conversation={self.claims.conversation_id} action={plan.action} "
                     f"reason={plan.reason_code} idle={idle}s unanswered={self._unanswered_nudges}")
        # 想的这会儿用户开口了，或者已经有别的一轮在进行：不说了。
        if plan.action != "speak" or self._user_turn_epoch != epoch or self._active_turns:
            return events
        text = plan.text.strip()
        if not has_words(text):
            return events
        # 用的是开场白那一类：角色主动说的话，计入用量但不占每日次数。
        turn_id, started = self._start(request_id, "opening")
        if not turn_id:
            return events
        events.extend(started)
        await self._emit_last(events, emit)
        task = asyncio.current_task()
        if task is not None:
            self.attach_task(turn_id, task)
        try:
            visible = await self._emit_delta(turn_id, "", text, events, emit)
            if turn_id in self._cancelled_turns or not await self._speak(turn_id):
                return events
            item = {"turn_id": turn_id, "text": "", "reply": visible[:MAX_OUTPUT_TEXT_LENGTH],
                    "occurred_at": int(time.time() * 1000), "opening": True}
            self._history.append(item)
            self._finished_openings.add(turn_id)
            self._unanswered_nudges += 1
            self._nudge_lines.append(visible)
            await self._write_history({**item, "usage": self._turn_usage(turn_id, "", visible)})
            self._next(events, "turn.completed", turn_id, {"text": visible, "opening": True, "proactive": True})
            await self._emit_last(events, emit)
            return events
        except _ReplyWithdrawn as withdrawn:
            # 没人问的话不需要换一句话来替代，直接收回。
            await self._drop_speech(turn_id)
            await self._report_safety(turn_id, "output", withdrawn.verdict)
            self._next(events, "reply.withdrawn", turn_id, {"text": ""})
            await self._emit_last(events, emit)
            self._next(events, "turn.cancelled", turn_id, {"reason": "nudge_withdrawn"})
            await self._emit_last(events, emit)
            return events
        except Exception:
            if turn_id not in self._cancelled_turns:
                self._next(events, "turn.cancelled", turn_id, {"reason": "nudge_failed"})
                await self._emit_last(events, emit)
            return events
        finally:
            self._active_turns.discard(turn_id)
            self._discard_turn_metrics(turn_id)
            await self._drop_speech(turn_id)

    async def handle_audio(self, item: AudioTurnInput) -> list[ConversationEvent]:
        turn_id, events = self.begin_audio(item)
        if not turn_id:
            return events
        return await self.finish_audio(turn_id, item, events)

    def begin_audio(self, item: AudioTurnInput) -> tuple[str, list[ConversationEvent]]:
        if self._expired():
            return "", self._session_expired()
        quota_error = self._quota_error()
        if quota_error is not None:
            return "", [quota_error]
        if "audio" not in self.claims.input_modes:
            return "", [self._event("error", self._next_error_sequence(), None, {"code": "input_mode_not_allowed", "message": "音频输入未授权", "retryable": False})]
        return self._start(item.request_id, "audio", item.segment_id, item.event_id)

    def begin_audio_transcript(self, request_id: str, text: str,
                               segment_id: str | None = None,
                               event_id: str | None = None) -> tuple[str, list[ConversationEvent]]:
        """Start an audio turn when streaming ASR already produced its final text.

        This avoids re-running the batch ASR provider and keeps authorization tied
        to the audio input scope even though the downstream LLM receives text.
        """
        if self._expired():
            return "", self._session_expired()
        quota_error = self._quota_error()
        if quota_error is not None:
            return "", [quota_error]
        if "audio" not in self.claims.input_modes:
            return "", [self._event("error", self._next_error_sequence(), None,
                                     {"code": "input_mode_not_allowed", "message": "音频输入未授权", "retryable": False})]
        if not isinstance(text, str) or not text.strip():
            raise ValueError("audio transcript is required")
        return self._start(request_id, "audio", segment_id, event_id)

    @staticmethod
    def _transcript_text(value: Any) -> str:
        """本地识别模型把文字连同语言、情绪标签放在一个字典里返回，对话只需要其中的文字。"""
        if isinstance(value, Mapping):
            value = value.get("content")
        return str(value or "").strip()

    async def finish_audio(self, turn_id: str, item: AudioTurnInput, events: list[ConversationEvent], emit=None) -> list[ConversationEvent]:
        if not turn_id:
            return events
        try:
            raw = base64.b64decode(item.data, validate=True)
            if self._speech_check is not None and not await asyncio.to_thread(self._speech_check, raw):
                # 这段声音里没有人在说话（风扇、键盘、各种杂音）：不交给识别，也不当成用户说了话。
                self._next(events, "turn.cancelled", turn_id, {"reason": "no_speech"})
                await self._emit_last(events, emit)
                return events
            model = self.runtime_models.get("ASR") or {}
            if self._asr is None:
                # 第一次加载本地识别模型要好几秒，放到线程里做，不能卡住其他用户正在进行的对话。
                self._asr = await asyncio.to_thread(self._asr_factory, model)
            if hasattr(self._asr, "to_playground_text"):
                text = await self._asr.to_playground_text(raw)
            else:
                text, _ = await self._asr.speech_to_text_wrapper([raw], self.claims.conversation_id, raise_errors=True)
            text = self._transcript_text(text)
            if not has_words(text):
                # 什么都没听清，或者只把噪声识别成了一个标点：不当成用户说了话，安静地结束这一轮，也不报错。
                self._next(events, "turn.cancelled", turn_id, {"reason": "no_speech"})
                await self._emit_last(events, emit)
                return events
            self._next(events, "asr.final", turn_id, {"text": text})
            await self._emit_last(events, emit)
            return await self._run_text(turn_id, text, events, emit)
        except Exception as exc:
            self._next(events, "error", turn_id, {"code": "turn_failed", "message": str(exc)[:400], "retryable": True})
            await self._emit_last(events, emit)
            return events
        finally:
            self._active_turns.discard(turn_id)

    async def cancel(self, turn_id: str) -> list[ConversationEvent]:
        if not isinstance(turn_id, str) or not turn_id:
            raise ValueError("turn_id is required")
        if (turn_id in self._cancelled_turns or turn_id in self._completed_turns
                or turn_id in self._finished_openings):
            return self._duplicate()
        if turn_id not in self._active_turns:
            events: list[ConversationEvent] = []
            self._next(events, "error", None, {
                "code": "unknown_turn",
                "message": "只能取消当前活跃轮次",
                "retryable": False,
            })
            return events
        self._cancelled_turns.add(turn_id)
        self._cancelled_emitted.add(turn_id)
        self._active_turns.discard(turn_id)
        task = self._turn_tasks.get(turn_id)
        if task is not None and not task.done() and task is not asyncio.current_task():
            task.cancel()
        events: list[ConversationEvent] = []
        self._next(events, "turn.cancelled", turn_id, {"reason": "client_cancelled"})
        return events

    def attach_task(self, turn_id: str, task: asyncio.Task[Any]) -> None:
        """Associate a running provider pipeline so cancellation can stop it."""
        if turn_id:
            self._turn_tasks[turn_id] = task

    def _next_error_sequence(self) -> int:
        self._sequence += 1
        return self._sequence

    def history(self, limit: int = 20) -> ConversationEvent:
        safe_limit = min(max(int(limit), 1), 50)
        events: list[ConversationEvent] = []
        self._next(events, "conversation.history", None, {"items": list(self._history)[-safe_limit:]})
        return events[0]

    def failure(self, turn_id: str, code: str, message: str, *, retryable: bool = True) -> ConversationEvent:
        self._active_turns.discard(turn_id)
        self._turn_tasks.pop(turn_id, None)
        events: list[ConversationEvent] = []
        self._next(events, "error", turn_id, {"code": code, "message": message, "retryable": retryable})
        return events[0]

    def _turn_usage(self, turn_id: str, text: str, visible: str) -> dict[str, Any]:
        """这一轮消耗了什么：字数、模型报告的 token、耗时。用于计量和每日额度。"""
        started = self._turn_started_at.pop(turn_id, None)
        first_text = self._turn_first_text_at.pop(turn_id, None)
        now = time.monotonic()
        model = self.runtime_models.get("LLM") or {}
        reported = getattr(self._llm, "last_usage", None)
        usage: dict[str, Any] = {
            "input_mode": self._turn_modes.pop(turn_id, "text"),
            "llm_model": str(model.get("model_name") or "")[:128],
            "input_chars": len(text),
            "reply_chars": len(visible),
            "tts_chars": len(visible) if "audio" in self.claims.output_modes else 0,
            "first_text_ms": int((first_text - started) * 1000) if started is not None and first_text is not None else None,
            "total_ms": int((now - started) * 1000) if started is not None else None,
        }
        if isinstance(reported, Mapping):
            for key in ("prompt_tokens", "completion_tokens"):
                value = reported.get(key)
                if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                    usage[key] = value
        return usage

    async def _write_history(self, item: dict[str, Any]) -> None:
        if self._history_writer is None:
            return
        try:
            result = self._history_writer(item)
            if hasattr(result, "__await__"):
                result = await result
        except Exception:
            return
        # manager-api 在记完这一轮后告诉运行时用户今天还剩多少次。用完了就不再开始新的一轮。
        if isinstance(result, Mapping):
            remaining = result.get("remaining_turns")
            if isinstance(remaining, int) and not isinstance(remaining, bool) and remaining <= 0:
                self._daily_quota_exhausted = True

    async def history_async(self, limit: int = 20) -> ConversationEvent:
        safe_limit = min(max(int(limit), 1), 50)
        if self._history_loader is not None:
            try:
                result = self._history_loader(safe_limit)
                if hasattr(result, "__await__"):
                    result = await result
                if isinstance(result, list):
                    events: list[ConversationEvent] = []
                    self._next(events, "conversation.history", None, {"items": result[-safe_limit:]})
                    return events[0]
            except Exception:
                pass
        return self.history(safe_limit)
