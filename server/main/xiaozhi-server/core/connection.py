import os
import sys
import copy
import hashlib
import inspect
import json
import re
import uuid
import time
import queue
import asyncio
import threading
import traceback
import subprocess
import websockets
import opuslib_next
import numpy as np

from core.utils.util import (
    extract_json_from_string,
    check_vad_update,
    check_asr_update,
    filter_sensitive_info,
)
from typing import Dict, Any
from collections import deque, OrderedDict
from core.utils.modules_initialize import (
    initialize_modules,
    initialize_tts,
    initialize_asr,
)
from core.handle.reportHandle import report, enqueue_tool_report
from core.providers.tts.default import DefaultTTS
from concurrent.futures import ThreadPoolExecutor
from core.utils.dialogue import Message, Dialogue
from core.providers.asr.dto.dto import InterfaceType
from core.handle.textHandle import handleTextMessage
from core.handle.sendAudioHandle import send_tts_message
from core.providers.tools.unified_tool_handler import UnifiedToolHandler
from plugins_func.loadplugins import auto_import_modules
from plugins_func.register import Action, ActionResponse
from core.auth import AuthenticationError
from config.config_loader import get_private_config_from_api
from core.providers.tts.dto.dto import ContentType, TTSMessageDTO, SentenceType
from config.logger import setup_logging, build_module_string, create_connection_logger
from config.manage_api_client import DeviceNotFoundException, DeviceBindException, generate_and_save_chat_title
from core.utils.prompt_manager import PromptManager
from core.utils.voiceprint_provider import VoiceprintProvider
from core.utils.util import get_system_error_response
from core.utils import textUtils
from core.utils import memory as memory_utils
from core.companion.identity import CompanionIdentity, is_profile_memory_namespace
from core.companion.companion_loop import CompanionLoop
from core.companion.proactive_planner import ProactivePlanner
from core.companion.audio_activity import AudioActivityGate
from core.companion.streaming_reply import CompanionStreamingReply
from core.companion.reply_protocol import (
    CompanionReplyStreamParser,
    strip_companion_reply_metadata,
)
from core.debug_events import DebugEventReporter
from core.debug_event_details import memory_query_details, module_details
from core.capabilities.cache import CapabilityBundleCache
from core.capabilities.client import CapabilityBundleClient
from core.capabilities.classifier import SkillClassifier
from core.capabilities.runtime import SkillTurnRuntime


TAG = __name__

auto_import_modules("plugins_func.functions")

_CAPABILITY_BUNDLE_CACHE = CapabilityBundleCache(CapabilityBundleClient())


class TTSException(RuntimeError):
    pass

# direct_answer 虚拟工具定义
# 不是真实工具，是路由机制：将"调不调工具"的二选一变为"调哪个"的多选，防止小模型误触发真实工具
DIRECT_ANSWER_TOOL = {
    "type": "function",
    "function": {
        "name": "direct_answer",
        "description": "当用户的请求不匹配其他任何工具时，可用此选项直接回复。将回复内容写在response参数里。",
        "parameters": {
            "type": "object",
            "properties": {
                "response": {
                    "type": "string",
                    "description": "你回复用户的完整内容",
                },
            },
            "required": ["response"],
        },
    },
}


class ConnectionHandler:
    def __init__(
            self,
            config: Dict[str, Any],
            _vad,
            _asr,
            _llm,
            _memory,
            _intent,
            server=None,
    ):
        self.common_config = config
        self.config = copy.deepcopy(config)
        self.session_id = str(uuid.uuid4())
        self.debug_events = None
        self._last_debug_heartbeat_at = 0.0
        self._debug_connection_closed = False
        self._debug_llm_started_at = {}
        self._debug_llm_first_visible = set()
        self._debug_llm_finished = OrderedDict()
        self._debug_lifecycle_lock = threading.Lock()
        self.logger = setup_logging()
        self.server = server  # 保存server实例的引用

        self.need_bind = False  # 是否需要绑定设备
        self.bind_completed_event = asyncio.Event()
        self.components_ready_event = asyncio.Event()
        self.bind_code = None  # 绑定设备的验证码
        self.last_bind_prompt_time = 0  # 上次播放绑定提示的时间戳(秒)
        self.bind_prompt_interval = 60  # 绑定提示播放间隔(秒)

        self.read_config_from_api = self.config.get("read_config_from_api", False)

        self.websocket: websockets.ServerConnection | None = None
        self.headers = None
        self.device_id = None
        self.client_ip = None
        self.prompt = None
        self.welcome_msg = None
        self.max_output_size = 0
        self.chat_history_conf = 0
        self.audio_format = "opus"
        self.sample_rate = 24000  # 默认采样率，从客户端 hello 消息中动态更新

        # 客户端状态相关
        self.client_abort = False
        self.client_is_speaking = False
        self.client_listen_mode = "auto"
        self.client_aec = False  # 是否启用了服务端AEC

        # 线程任务相关
        self.loop = None  # 在 handle_connection 中获取运行中的事件循环
        self.stop_event = threading.Event()
        self.executor = ThreadPoolExecutor(max_workers=5)

        # 添加上报线程池
        self.report_queue = queue.Queue()
        self.report_thread = None
        # 未来可以通过修改此处，调节asr的上报和tts的上报，目前默认都开启
        self.report_asr_enable = self.read_config_from_api
        self.report_tts_enable = self.read_config_from_api

        # 依赖的组件
        self.vad = None
        self.asr = None
        self.tts = None
        self._asr = _asr
        self._vad = _vad
        self.llm = _llm
        self.memory = _memory
        self.intent = _intent
        self.companion_identity = None
        self._companion_loop = None
        self._proactive_history = deque(maxlen=3)
        self.last_confirmed_user_activity = time.monotonic()
        self._activity_gate = AudioActivityGate()
        self._configure_activity_gate()
        self.proactive_playback_active = False
        self._proactive_sentence_id = None
        self._proactive_audio_started = False
        self._pending_proactive_messages = {}
        self._proactive_memory_saved_ids = set()
        self._proactive_memory_tasks = set()
        self._proactive_completion_futures = {}
        self._proactive_state_lock = threading.Lock()
        self._closed = False
        self.chat_in_progress = False
        # Ordinary chat holds this state gate for its complete state
        # transition. Proactive planning only tries it around snapshots and
        # commits; its provider call uses the separate provider gate below so
        # a stalled plan cannot hold this state gate.
        self.companion_mutex = threading.Lock()
        # Serializes actual LLM/provider calls for this connection. The state
        # mutex is held for a user turn, while this separate gate is held only
        # by a chat or planner while its provider call is in flight.
        self._companion_provider_lock = threading.Lock()
        # Public alias used by companion integrations; both names always point
        # at the same per-connection lock.
        self.companion_provider_gate = self._companion_provider_lock
        self.capability_bundle = None
        # The active agent version is captured once for this connection.
        self._connection_capability_bundle = None
        self._turn_capability_bundle = None
        self._skill_runtime = SkillTurnRuntime()
        self._skill_turn = None
        self._skill_turn_started_at = None
        self._skill_result_class = None
        self._skill_turn_finished = False

        # 为每个连接单独管理声纹识别
        self.voiceprint_provider = None

        # vad相关变量
        self.client_audio_buffer = bytearray()
        self.client_have_voice = False
        self.client_voice_window = deque(maxlen=5)
        self.first_activity_time = 0.0  # 记录首次活动的时间（毫秒）
        self.last_activity_time = 0.0  # 统一的活动时间戳（毫秒）
        self.vad_last_voice_time = 0.0  # 记录用户最后一次说话的时间（毫秒）
        self.client_voice_stop = False
        self.last_is_voice = False

        # asr相关变量
        # 因为实际部署时可能会用到公共的本地ASR，不能把变量暴露给公共ASR
        # 所以涉及到ASR的变量，需要在这里定义，属于connection的私有变量
        self.asr_audio = []  # 存储PCM帧列表，供VAD和ASR共享
        self.asr_audio_queue = queue.Queue()
        self.current_speaker = None  # 存储当前说话人
        self.introduced_speakers = set()  # 已"首次引入"的说话人，控制只在首轮带名字
        self.system_introduced_speakers = set()  # 已在 system 注入过身份的说话人，控制 system 身份只首轮出现

        # llm相关变量
        self.dialogue = Dialogue()

        # tts相关变量
        self.sentence_id = None
        # 处理TTS响应没有文本返回
        self.tts_MessageText = ""

        # iot相关变量
        self.iot_descriptors = {}
        self.func_handler = None

        self.cmd_exit = self.config["exit_commands"]

        # 是否在聊天结束后关闭连接
        self.close_after_chat = False
        self.load_function_plugin = False
        self.intent_type = "nointent"

        self.timeout_seconds = (
                int(self.config.get("close_connection_no_voice_time", 120)) + 60
        )  # 在原来第一道关闭的基础上加60秒，进行二道关闭
        self.timeout_task = None
        self._background_initialize_task = None
        self._component_init_future = None
        self._component_channel_future = None
        self._private_config_signature = None
        self._companion_chat_queue = deque()
        self._companion_chat_queue_lock = threading.Lock()
        self._companion_chat_dispatch_thread = None
        self._companion_chat_pending = False

        # {"mcp":true} 表示启用MCP功能
        self.features = {}
        self.hello_received = False
        self._companion_start_task = None

        # 标记连接是否来自MQTT
        self.conn_from_mqtt_gateway = False

        # 初始化提示词管理器
        self.prompt_manager = PromptManager(self.config, self.logger)

        # 初始化通话状态
        self.calling = False
        # 标记当前是否为来电接听模式
        self.incoming_call = None

    async def handle_connection(self, ws: websockets.ServerConnection):
        try:
            # 获取运行中的事件循环（必须在异步上下文中）
            self.loop = asyncio.get_running_loop()

            # 获取并验证headers
            self.headers = dict(ws.request.headers)
            real_ip = self.headers.get("x-real-ip") or self.headers.get(
                "x-forwarded-for"
            )
            if real_ip:
                self.client_ip = real_ip.split(",")[0].strip()
            else:
                self.client_ip = ws.remote_address[0]
            self.logger.bind(tag=TAG).info(
                f"{self.client_ip} conn - Headers: {self.headers}"
            )

            self.device_id = self.headers.get("device-id", None)
            try:
                self.debug_events = DebugEventReporter(self.device_id, self.session_id)
            except Exception as error:
                self.debug_events = None
                self.logger.bind(tag=TAG).debug(
                    f"调试事件初始化失败: {type(error).__name__}"
                )
            self.emit_debug_event(
                "device", "connection.opened", "info", "设备连接已建立"
            )

            # 认证通过,继续处理
            self.websocket = ws
            if self.server is not None:
                self.server.register_connection(self)

            # 检查是否来自MQTT连接
            request_path = ws.request.path
            self.conn_from_mqtt_gateway = request_path.endswith("?from=mqtt_gateway")
            if self.conn_from_mqtt_gateway:
                self.logger.bind(tag=TAG).info("连接来自:MQTT网关")

            # 初始化活动时间戳
            self.first_activity_time = time.time() * 1000
            self.last_activity_time = time.time() * 1000

            # 启动超时检查任务
            self.timeout_task = asyncio.create_task(self._check_timeout())

            # 启动AEC缓存清理任务
            self._aec_cache_cleanup_task = asyncio.create_task(self._check_aec_cache_expiry())

            self.welcome_msg = self.config["xiaozhi"]
            self.welcome_msg["session_id"] = self.session_id

            # 从配置中读取采样率
            self.sample_rate = self.welcome_msg["audio_params"]["sample_rate"]
            self.logger.bind(tag=TAG).info(f"配置输出音频采样率为: {self.sample_rate}")

            # 在后台初始化配置和组件（完全不阻塞主循环）
            initialize_task = asyncio.create_task(self._background_initialize())
            self._background_initialize_task = initialize_task

            def clear_initialize_task(done_task):
                if getattr(self, "_background_initialize_task", None) is done_task:
                    self._background_initialize_task = None

            initialize_task.add_done_callback(clear_initialize_task)

            try:
                async for message in self.websocket:
                    await self._route_message(message)
            except websockets.exceptions.ConnectionClosed:
                self.logger.bind(tag=TAG).info("客户端断开连接")

        except AuthenticationError as e:
            self.logger.bind(tag=TAG).error(f"Authentication failed: {str(e)}")
            return
        except Exception as e:
            self.emit_debug_event(
                "device",
                "connection.failed",
                "error",
                "设备连接异常",
                details={
                    "errorClass": type(e).__name__,
                    "message": "连接处理异常",
                },
            )
            stack_trace = traceback.format_exc()
            self.logger.bind(tag=TAG).error(f"Connection error: {str(e)}-{stack_trace}")
            return
        finally:
            if self.server is not None:
                self.server.unregister_connection(self)
            try:
                await self._save_and_close(ws)
            except Exception as final_error:
                self.logger.bind(tag=TAG).error(f"最终清理时出错: {final_error}")
                # 确保即使保存记忆失败，也要关闭连接
                try:
                    await self.close(ws)
                except Exception as close_error:
                    self.logger.bind(tag=TAG).error(
                        f"强制关闭连接时出错: {close_error}"
                    )

    async def _save_and_close(self, ws):
        """保存记忆并关闭连接"""
        try:
            # 只有启用聊天记录时才生成标题；关闭记录时没有可关联的历史会话。
            if self.session_id and self.chat_history_conf != 0:
                def generate_title_task():
                    try:
                        loop = asyncio.new_event_loop()
                        asyncio.set_event_loop(loop)
                        loop.run_until_complete(
                            generate_and_save_chat_title(self.session_id)
                        )
                    except Exception as e:
                        self.logger.bind(tag=TAG).error(f"生成标题失败: {e}")
                    finally:
                        try:
                            loop.close()
                        except Exception:
                            pass

                threading.Thread(target=generate_title_task, daemon=True).start()

            # 在当前事件循环保存记忆，避免跨事件循环复用异步 MemoryCore 客户端。
            if self.memory and self._memory_debug_skip_reason() is None:
                save_started = time.monotonic()
                message_snapshot = [
                    message
                    for message in self.dialogue.dialogue
                    if getattr(message, "source", "conversation") != "proactive"
                ]
                try:
                    self.emit_debug_event(
                        "model_tool",
                        "memory.save_started",
                        "info",
                        "记忆保存已开始",
                        details=module_details(self.config, "Memory"),
                    )
                    await asyncio.wait_for(
                        self.memory.save_memory(message_snapshot, self.session_id),
                        timeout=5,
                    )
                    self.emit_debug_event(
                        "model_tool",
                        "memory.save_completed",
                        "info",
                        "记忆保存已完成",
                        details={
                            **module_details(self.config, "Memory"),
                            "messageCount": len(message_snapshot),
                        },
                        duration_ms=max(
                            0, int((time.monotonic() - save_started) * 1000)
                        ),
                    )
                except Exception as e:
                    self.emit_debug_event(
                        "model_tool",
                        "memory.save_failed",
                        "error",
                        "记忆保存失败",
                        details={"errorClass": type(e).__name__},
                        duration_ms=max(
                            0, int((time.monotonic() - save_started) * 1000)
                        ),
                    )
                    self.logger.bind(tag=TAG).error(f"保存记忆失败: {e}")
            else:
                self.emit_debug_event(
                    "model_tool",
                    "memory.save_skipped",
                    "info",
                    "记忆保存已跳过",
                    details={
                        "reason": self._memory_debug_skip_reason()
                        or "memory_disabled"
                    },
                )
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"保存记忆失败: {e}")
        finally:
            # 立即关闭连接，不等待记忆保存完成
            try:
                await self.close(ws)
            except Exception as close_error:
                self.logger.bind(tag=TAG).error(
                    f"保存记忆后关闭连接失败: {close_error}"
                )

    async def _discard_message_with_bind_prompt(self):
        """丢弃消息并检查是否需要播放绑定提示"""
        current_time = time.time()
        # 检查是否需要播放绑定提示
        if current_time - self.last_bind_prompt_time >= self.bind_prompt_interval:
            self.last_bind_prompt_time = current_time
            # 复用现有的绑定提示逻辑
            from core.handle.receiveAudioHandle import check_bind_device

            asyncio.create_task(check_bind_device(self))

    async def _route_message(self, message):
        """消息路由"""
        # 检查是否已经获取到真实的绑定状态
        if not self.bind_completed_event.is_set():
            # 还没有获取到真实状态，等待直到获取到真实状态或超时
            try:
                await asyncio.wait_for(self.bind_completed_event.wait(), timeout=1)
            except asyncio.TimeoutError:
                # 超时仍未获取到真实状态，丢弃消息
                await self._discard_message_with_bind_prompt()
                return

        # 已经获取到真实状态，检查是否需要绑定
        if self.need_bind:
            # 需要绑定，丢弃消息
            await self._discard_message_with_bind_prompt()
            return

        # 不需要绑定，继续处理消息

        requires_components = isinstance(message, bytes)
        if isinstance(message, str):
            try:
                parsed_message = json.loads(message)
                requires_components = (
                    isinstance(parsed_message, dict)
                    and parsed_message.get("type") == "listen"
                )
            except json.JSONDecodeError:
                pass
        if requires_components and not self.components_ready_event.is_set():
            try:
                await asyncio.wait_for(
                    self.components_ready_event.wait(), timeout=10
                )
            except asyncio.TimeoutError:
                self.logger.bind(tag=TAG).warning("连接组件尚未就绪，忽略当前消息")
                return

        if isinstance(message, str):
            await handleTextMessage(self, message)
        elif isinstance(message, bytes):
            if self.vad is None or self.asr is None:
                return

            # 处理来自MQTT网关的音频包
            if self.conn_from_mqtt_gateway and len(message) >= 16:
                handled = await self._process_mqtt_audio_message(message)
                if handled:
                    return

            # 入口处直接解码PCM，避免VAD和ASR重复解码
            pcm_frame = self._decode_opus_packet(message)
            if pcm_frame:
                self.asr_audio_queue.put(pcm_frame)

    async def _process_mqtt_audio_message(self, message):
        """
        处理来自MQTT网关的音频消息，解析16字节头部并提取音频数据，在入队前进行AEC处理

        Args:
            message: 包含头部的音频消息

        Returns:
            bool: 是否成功处理了消息
        """
        try:
            # 解析timestamp
            timestamp = int.from_bytes(message[8:12], "big")

            audio_data = message[16:]
            # 入口直接解码PCM
            pcm_frame = self._decode_opus_packet(audio_data)
            if not pcm_frame:
                return True

            # AEC处理：如果timestamp>0且启用了AEC
            if timestamp > 0 and self.client_aec:
                pcm_frame = self._apply_aec(timestamp, pcm_frame)

            self.asr_audio_queue.put(pcm_frame)
            return True
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"解析WebSocket音频包失败: {e}")

        # 处理失败，返回False表示需要继续处理
        return False

    def _apply_aec(self, timestamp: int, pcm_frame: bytes) -> bytes:
        """应用AEC处理 - 综合算法：互相关延迟估计 + Wiener滤波 + 频谱减法"""
        try:
            if not pcm_frame or len(pcm_frame) == 0:
                return pcm_frame

            if not hasattr(self, "aec_audio_cache") or not self.aec_audio_cache:
                return pcm_frame

            mic_audio = np.frombuffer(pcm_frame, dtype=np.int16).astype(np.float32)
            mic_rms = np.sqrt(np.mean(mic_audio ** 2))

            if mic_rms < 100:
                return pcm_frame

            sorted_timestamps = sorted(self.aec_audio_cache.keys())
            if len(sorted_timestamps) < 2:
                return pcm_frame

            # ========== 匹配参考帧（对数功率谱匹配） ==========
            n = len(mic_audio)

            # 找最接近的timestamp作为起点
            closest_idx = min(range(len(sorted_timestamps)), key=lambda i: abs(sorted_timestamps[i] - timestamp))

            # 预计算 mic_audio 的对数功率谱（循环内共用，避免重复FFT）
            mic_window = np.hanning(n)
            mic_fft = np.fft.rfft(mic_audio * mic_window)
            mic_psd = np.abs(mic_fft) ** 2
            mic_log_psd = 10 * np.log10(mic_psd + 1e-8)
            mic_P_xx = np.dot(mic_log_psd, mic_log_psd)

            # 用对数功率谱匹配找最佳帧：前后各找2帧
            best_corr = -1
            best_ref_idx = closest_idx
            best_ref_rms = 0.0

            for offset in range(-2, 3):  # T-2, T-1, T, T+1, T+2
                test_idx = closest_idx + offset
                if test_idx < 0 or test_idx >= len(sorted_timestamps):
                    continue
                test_ts = sorted_timestamps[test_idx]
                test_ref = np.frombuffer(self.aec_audio_cache[test_ts], dtype=np.int16).astype(np.float32)
                test_ref_rms = np.sqrt(np.mean(test_ref ** 2))
                if test_ref_rms < 50:
                    continue

                # 对数功率谱相关性
                test_window = np.hanning(len(test_ref))
                test_fft = np.fft.rfft(test_ref * test_window)
                test_psd = np.abs(test_fft) ** 2
                test_log_psd = 10 * np.log10(test_psd + 1e-8)
                P_xy = np.dot(mic_log_psd, test_log_psd)
                P_yy = np.dot(test_log_psd, test_log_psd)
                corr = abs(P_xy) / (np.sqrt(mic_P_xx) * np.sqrt(P_yy) + 1e-8)

                if corr > best_corr:
                    best_corr = corr
                    best_ref_idx = test_idx
                    best_ref_rms = test_ref_rms

            best_ts = sorted_timestamps[best_ref_idx]
            best_ref = np.frombuffer(self.aec_audio_cache[best_ts], dtype=np.int16).astype(np.float32)
            ref_rms = best_ref_rms

            if ref_rms < 50:
                return pcm_frame

            # 对齐参考信号（直接截取相同长度）
            aligned_ref = best_ref[:n]
            if len(aligned_ref) < n:
                aligned_ref = np.pad(aligned_ref, (0, n - len(aligned_ref)))

            # ========== 频域 AEC 处理（谱减法） ==========
            # 时域信号经过声学路径后相位失真，导致时域相关性低且P_xy正负不定
            # 频域幅度谱不受相位影响，对数功率谱相关性稳定在0.97+
            # 公式：result_mag = max(|mic_fft| - |ref_fft| * scale * coef, 0)

            mic_mag = np.abs(mic_fft)
            mic_phase = np.angle(mic_fft)
            ref_fft = np.fft.rfft(aligned_ref * np.hanning(n))
            ref_mag = np.abs(ref_fft)

            # 频域计算回声比例 scale
            scale = np.sum(mic_mag * ref_mag) / (np.dot(ref_mag, ref_mag) + 1e-8)

            # 自适应系数：根据scale和coh动态调整
            # scale大（回声强）-> coef大；coh高（匹配准）-> coef大
            raw_coef = 1.0 + scale * 3 + (best_corr - 0.97) * 30
            coef = max(0.5, min(3.0, raw_coef))

            # 谱减法（过减 + 半波整流）
            echo_mag = ref_mag * scale * coef
            result_mag = np.maximum(mic_mag - echo_mag * 1.5, mic_mag * 0.1)

            # 保留相位重建信号
            result_fft = result_mag * np.exp(1j * mic_phase)
            output = np.fft.irfft(result_fft, n)

            # 高置信度是纯回声时，再压一下确保VAD检测不到
            if best_corr >= 0.97 and ref_rms > 500:
                output = output * 0.3

            # 后处理：限幅
            output = np.clip(output, -32768, 32767)

            # 转换为bytes
            result = output.astype(np.int16).tobytes()

            return result

        except Exception as e:
            self.logger.bind(tag=TAG).warning(f"[AEC] 处理失败: {e}")
            return pcm_frame

    def _decode_opus_packet(self, opus_packet: bytes) -> bytes:
        """
        解码Opus数据包为PCM数据

        Args:
            opus_packet: Opus编码的音频数据

        Returns:
            bytes: 解码后的PCM数据，失败返回None
        """
        try:
            if not opus_packet or len(opus_packet) == 0:
                return None

            self._init_connection_state(self)
            pcm_frame = self._connection_opus_decoder.decode(opus_packet, 960)
            return pcm_frame
        except Exception as e:
            self.logger.bind(tag=TAG).debug(f"Opus解码失败: {e}")
            return None

    def _init_connection_state(self, conn):
        """为连接初始化独立的Opus解码器"""
        if not hasattr(conn, "_connection_opus_decoder"):
            conn._connection_opus_decoder = opuslib_next.Decoder(16000, 1)

    async def handle_restart(self, message):
        """处理服务器重启请求"""
        try:

            self.logger.bind(tag=TAG).info("收到服务器重启指令，准备执行...")

            # 发送确认响应
            await self.websocket.send(
                json.dumps(
                    {
                        "type": "server",
                        "status": "success",
                        "message": "服务器重启中...",
                        "content": {"action": "restart"},
                    }
                )
            )

            # 异步执行重启操作
            def restart_server():
                """实际执行重启的方法"""
                time.sleep(1)
                self.logger.bind(tag=TAG).info("执行服务器重启...")
                subprocess.Popen(
                    [sys.executable, "app.py"],
                    stdin=sys.stdin,
                    stdout=sys.stdout,
                    stderr=sys.stderr,
                    start_new_session=True,
                )
                os._exit(0)

            # 使用线程执行重启避免阻塞事件循环
            threading.Thread(target=restart_server, daemon=True).start()

        except Exception as e:
            self.logger.bind(tag=TAG).error(f"重启失败: {str(e)}")
            await self.websocket.send(
                json.dumps(
                    {
                        "type": "server",
                        "status": "error",
                        "message": f"Restart failed: {str(e)}",
                        "content": {"action": "restart"},
                    }
                )
            )

    def _initialize_components(self):
        try:
            if getattr(self, "_closed", False) or (
                getattr(self, "stop_event", None) is not None
                and self.stop_event.is_set()
            ):
                return

            def wait_for_component(coroutine):
                future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
                self._component_channel_future = future
                try:
                    future.result(
                        timeout=float(self.config.get("component_init_timeout", 15))
                    )
                except Exception:
                    future.cancel()
                    raise
                finally:
                    if getattr(self, "_component_channel_future", None) is future:
                        self._component_channel_future = None

            def initialization_cancelled():
                return getattr(self, "_closed", False) or (
                    getattr(self, "stop_event", None) is not None
                    and self.stop_event.is_set()
                )

            def close_component(component):
                close = getattr(component, "close", None)
                if not callable(close):
                    return
                try:
                    result = close()
                    if inspect.isawaitable(result):
                        wait_for_component(result)
                except Exception as error:
                    try:
                        self.logger.bind(tag=TAG).debug(
                            f"关闭初始化组件失败: {type(error).__name__}"
                        )
                    except Exception:
                        pass

            if self.tts is None:
                if getattr(self, "_closed", False) or (
                    getattr(self, "stop_event", None) is not None
                    and self.stop_event.is_set()
                ):
                    return
                self.tts = self._initialize_tts()
            # 打开语音合成通道
            wait_for_component(self.tts.open_audio_channels(self))
            if initialization_cancelled():
                close_component(self.tts)
                return
            if self.need_bind:
                self.bind_completed_event.set()
                return
            self.selected_module_str = build_module_string(
                self.config.get("selected_module", {})
            )
            self.logger = create_connection_logger(self.selected_module_str)

            """初始化组件"""
            if self.config.get("prompt") is not None:
                user_prompt = self.config["prompt"]
                # 使用快速提示词进行初始化
                prompt = self.prompt_manager.get_quick_prompt(user_prompt)
                self.change_system_prompt(prompt)
                self.logger.bind(tag=TAG).info(
                    f"快速初始化组件: prompt成功 {prompt[:50]}..."
                )

            """初始化本地组件"""
            if initialization_cancelled():
                close_component(self.tts)
                return
            if self.vad is None:
                self.vad = self._vad
            if self.asr is None:
                self.asr = self._initialize_asr()
            if initialization_cancelled():
                close_component(self.asr)
                close_component(self.tts)
                return

            # 初始化声纹识别
            self._initialize_voiceprint()
            # 打开语音识别通道
            wait_for_component(self.asr.open_audio_channels(self))
            if initialization_cancelled():
                close_component(self.asr)
                close_component(self.tts)
                return

            """加载记忆"""
            self._initialize_memory()
            if getattr(self, "_closed", False) or (
                getattr(self, "stop_event", None) is not None
                and self.stop_event.is_set()
            ):
                close_component(self.asr)
                close_component(self.tts)
                return
            """加载意图识别"""
            self._initialize_intent()
            if initialization_cancelled():
                close_component(self.asr)
                close_component(self.tts)
                return
            """初始化上报线程"""
            self._init_report_threads()
            if initialization_cancelled():
                close_component(self.asr)
                close_component(self.tts)
                return
            """更新系统提示词"""
            self._init_prompt_enhancement()
            if initialization_cancelled():
                close_component(self.asr)
                close_component(self.tts)
                return
            """注入工具调用few-shot示例（仅function_call模式）"""
            self._inject_tool_call_fewshot()
            if not getattr(self, "_closed", False) and not (
                getattr(self, "stop_event", None) is not None
                and self.stop_event.is_set()
            ):
                self.loop.call_soon_threadsafe(self.components_ready_event.set)
                self.loop.call_soon_threadsafe(self._start_companion_loop)
            else:
                close_component(self.asr)
                close_component(self.tts)

        except Exception as e:
            self.logger.bind(tag=TAG).error(f"实例化组件失败: {e}")

    def _init_prompt_enhancement(self):

        # 更新上下文信息
        self.prompt_manager.update_context_info(self, self.client_ip)
        enhanced_prompt = self.prompt_manager.build_enhanced_prompt(
            self.config["prompt"],
            self.device_id,
            self.client_ip,
            emoji_enabled=(self.features or {}).get("emoji", True),
        )
        if enhanced_prompt:
            self.change_system_prompt(enhanced_prompt)
            self.logger.bind(tag=TAG).debug("系统提示词已增强更新")

    def _start_companion_loop(self):
        if getattr(self, "_closed", False) or (
            getattr(self, "stop_event", None) is not None
            and self.stop_event.is_set()
        ):
            return
        companion = self.config.get("companion", {})
        if not isinstance(companion, dict):
            return
        if not companion.get("enabled") or companion.get("mode") != "proactive":
            return
        if self._companion_loop is not None:
            return
        if companion.get("require_realtime_aec", False) and not getattr(self, "hello_received", True):
            loop = getattr(self, "loop", None)
            if loop is not None and loop.is_running() and (
                getattr(self, "_companion_start_task", None) is None
                or self._companion_start_task.done()
            ):
                async def retry_after_hello():
                    timeout = companion.get("hello_capability_timeout_seconds", 5)
                    try:
                        timeout = max(0.5, min(30.0, float(timeout)))
                    except (TypeError, ValueError):
                        timeout = 5.0
                    deadline = loop.time() + timeout
                    while not getattr(self, "hello_received", False) and loop.time() < deadline:
                        await asyncio.sleep(0.1)
                    self._companion_start_task = None
                    if getattr(self, "hello_received", False):
                        self._start_companion_loop()
                    else:
                        companion["mode"] = "turn_based"
                        self.emit_debug_event(
                            "device",
                            "companion.proactive_unavailable",
                            "warning",
                            "设备能力握手超时，已回退对答模式",
                            details={"reason": "hello_timeout"},
                        )

                self._companion_start_task = loop.create_task(retry_after_hello())
            return
        if not self._proactive_capability_supported():
            companion["mode"] = "turn_based"
            self.emit_debug_event(
                "device",
                "companion.proactive_unavailable",
                "warning",
                "设备不支持主动陪伴所需的 realtime/AEC",
                details={"reason": "realtime_aec_required"},
            )
            self.logger.bind(tag=TAG).warning(
                "设备未协商 realtime/AEC，主动陪伴已回退对答模式"
            )
            return
        if getattr(self, "llm", None) is None or getattr(self, "tts", None) is None:
            self.logger.bind(tag=TAG).warning("主动陪伴缺少 LLM 或 TTS，保持对答模式")
            return
        try:
            max_chars = int(companion.get("proactive_max_chars", 80))
        except (TypeError, ValueError):
            max_chars = 80
        try:
            max_context_chars = int(companion.get("proactive_max_context_chars", 12000))
        except (TypeError, ValueError):
            max_context_chars = 12000
        try:
            min_memory_confidence = float(companion.get("proactive_min_memory_confidence", 0.5))
        except (TypeError, ValueError):
            min_memory_confidence = 0.5
        planner = ProactivePlanner(
            self.llm,
            global_prompt=companion.get("proactive_planner_prompt", ""),
            agent_guidance=companion.get("proactive_guidance", ""),
            max_chars=max_chars,
            max_context_chars=max_context_chars,
            min_memory_confidence=min_memory_confidence,
            session_id=f"{getattr(self, 'session_id', 'connection')}:proactive-planner",
        )
        self._companion_loop = CompanionLoop(
            self,
            planner,
            context_provider=self._proactive_context,
            speaker=self._speak_proactive,
        )
        self._companion_loop.start()
        self._request_proactive_listening()
        self.logger.bind(tag=TAG).info("主动陪伴循环已启动")

    def _request_proactive_listening(self):
        """Ask a device client to keep its microphone in realtime mode."""
        websocket = getattr(self, "websocket", None)
        if websocket is None or not getattr(self, "device_id", None):
            return
        if getattr(self, "client_listen_mode", "auto") == "realtime":
            return
        loop = getattr(self, "loop", None)
        if loop is None:
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                return
        async def send_request():
            companion = self.config.get("companion", {})
            for attempt in range(1, 4):
                if getattr(self, "_closed", False) or (
                    getattr(self, "stop_event", None) is not None
                    and self.stop_event.is_set()
                ):
                    return
                try:
                    await websocket.send(
                        json.dumps(
                            {
                                "session_id": getattr(self, "session_id", ""),
                                "type": "listen",
                                "state": "start",
                                "mode": "realtime",
                            },
                            ensure_ascii=False,
                        )
                    )
                    self.client_listen_mode = "realtime"
                    self.emit_debug_event(
                        "audio",
                        "companion.realtime_requested",
                        "info",
                        "已请求设备进入 realtime 收音",
                        details={"attempt": attempt},
                    )
                    return
                except Exception as error:
                    self.logger.bind(tag=TAG).warning(
                        f"请求设备 realtime 收音失败: {type(error).__name__}"
                    )
                    if attempt < 3:
                        await asyncio.sleep(0.25 * attempt)
            companion["mode"] = "turn_based"
            self.emit_debug_event(
                "device",
                "companion.proactive_unavailable",
                "warning",
                "设备无法进入 realtime 收音，已回退对答模式",
                details={"reason": "realtime_request_failed", "attempts": 3},
            )

        if loop.is_running():
            loop.call_soon_threadsafe(lambda: asyncio.create_task(send_request()))

    def _proactive_capability_supported(self):
        companion = self.config.get("companion", {})
        if not companion.get("require_realtime_aec", False):
            return True
        features = self.features if isinstance(getattr(self, "features", None), dict) else {}
        return bool(
            getattr(self, "client_aec", False)
            or features.get("aec")
            or features.get("realtime")
        )

    async def _proactive_context(self):
        self.logger.bind(tag=TAG).info("主动陪伴开始准备规划上下文")
        self._ensure_proactive_state()
        lock = getattr(self, "companion_mutex", None)

        def snapshot_messages():
            return [
                {
                    "role": item.role,
                    "content": item.content or "",
                    "source": getattr(item, "source", "conversation"),
                }
                for item in list(self.dialogue.dialogue)
                if item.role in {"user", "assistant"} and not item.is_temporary
            ]

        if lock is not None and hasattr(lock, "acquire"):
            if not lock.acquire(False):
                return {
                    "recent_turns": [],
                    "proactive_history": [],
                    "memories": [],
                    "memory_degraded": True,
                    "has_recent_context": False,
                    "state_busy": True,
                    "idle_seconds": 0,
                }
            try:
                messages = snapshot_messages()
                proactive_history = list(self._proactive_history)
            finally:
                lock.release()
        else:
            messages = snapshot_messages()
            proactive_history = list(self._proactive_history)
        memories = []
        memory_degraded = self.memory is None
        if self.memory is not None:
            try:
                query = "近期情绪、未完话题、计划和重要生活细节"
                query_candidates = getattr(self.memory, "query_memory_candidates", None)
                if callable(query_candidates):
                    memory_result = query_candidates(query)
                    if inspect.isawaitable(memory_result):
                        memory_result = await memory_result
                else:
                    memory_result = await self.memory.query_memory(query)
                memories = self._proactive_memory_candidates(memory_result)
                raw_has_memory = bool(
                    memory_result.get("items")
                    or memory_result.get("memories")
                    if isinstance(memory_result, dict)
                    else memory_result
                )
                confidence_threshold = self._proactive_memory_confidence_threshold()
                memory_degraded = raw_has_memory and not any(
                    self._memory_candidate_confident(item, confidence_threshold)
                    for item in memories
                )
            except Exception as error:
                memory_degraded = True
                self.logger.bind(tag=TAG).debug(f"主动陪伴记忆查询失败: {type(error).__name__}")
        self.logger.bind(tag=TAG).info(
            f"主动陪伴规划上下文准备完成: turns={min(len(messages), 24)}, memories={len(memories)}"
        )
        idle_seconds = max(
            0,
            int(time.monotonic() - getattr(self, "last_confirmed_user_activity", time.monotonic())),
        )
        last_proactive_index = max(
            (
                index
                for index, item in enumerate(messages[-12:])
                if item.get("source") == "proactive"
            ),
            default=-1,
        )
        has_recent_user_context = any(
            item.get("role") == "user"
            and item.get("source") != "proactive"
            and str(item.get("content", "")).strip()
            and "[敏感内容已省略]" not in str(item.get("content", ""))
            for item in messages[-12:][last_proactive_index + 1 :]
        )
        return {
            "recent_turns": messages[-24:],
            "proactive_history": proactive_history,
            "memories": memories,
            "memory_degraded": memory_degraded,
            "has_recent_context": has_recent_user_context,
            "idle_seconds": idle_seconds,
        }

    @staticmethod
    def _proactive_memory_candidates(memory_result):
        """Normalize provider-specific memory responses to bounded planner candidates."""
        if isinstance(memory_result, dict):
            raw_items = memory_result.get("items") or memory_result.get("memories") or []
        elif isinstance(memory_result, (list, tuple)):
            raw_items = memory_result
        elif isinstance(memory_result, str):
            raw_items = [
                {"id": f"runtime-memory-{index}", "content": line.strip(), "confidence": 0.7}
                for index, line in enumerate(memory_result.splitlines())
                if line.strip() and not line.strip().startswith("[")
            ]
        else:
            raw_items = []
        candidates = []
        for index, item in enumerate(raw_items if isinstance(raw_items, (list, tuple)) else []):
            if isinstance(item, str):
                item = {"id": f"runtime-memory-{index}", "content": item, "confidence": 0.7}
            if not isinstance(item, dict) or not item.get("content"):
                continue
            candidate = {
                "id": str(item.get("id") or f"runtime-memory-{index}")[:128],
                "content": str(item.get("content"))[:1000],
                "confidence": item.get("confidence", 0.7),
                "source": str(item.get("source") or "memory")[:32],
            }
            candidates.append(candidate)
        return candidates[:5]

    def _proactive_memory_confidence_threshold(self):
        raw = self.config.get("companion", {}).get(
            "proactive_min_memory_confidence", 0.5
        )
        try:
            return min(1.0, max(0.0, float(raw)))
        except (TypeError, ValueError):
            return 0.5

    @staticmethod
    def _memory_candidate_confident(item, min_confidence=0.5):
        try:
            content = str(item.get("content", ""))
            if re.search(
                r"密码|口令|令牌|token|api[_ -]?key|银行卡|身份证|病历|诊断|(?<!\d)\d{11,}(?!\d)",
                content,
                re.IGNORECASE,
            ):
                return False
            return float(item.get("confidence", 0.0)) >= float(min_confidence)
        except (TypeError, ValueError, AttributeError):
            return False

    async def _speak_proactive(self, plan):
        self._ensure_proactive_state()
        state_gate = getattr(self, "companion_mutex", None)
        gate_acquired = state_gate.acquire(False) if state_gate is not None else True
        if not gate_acquired:
            return False
        try:
            self._proactive_state_lock.acquire()
            try:
                if (
                    self._closed
                    or (
                        getattr(self, "stop_event", None) is not None
                        and self.stop_event.is_set()
                    )
                    or self.tts is None
                    or self.client_is_speaking
                ):
                    return False
                sentence_id = uuid.uuid4().hex
                self.sentence_id = sentence_id
                self._proactive_sentence_id = sentence_id
                self.client_abort = False
                self.proactive_playback_active = True
                self._proactive_audio_started = False
                self.client_is_speaking = True
                self._pending_proactive_messages[sentence_id] = plan
                loop = getattr(self, "loop", None)
                if loop is None:
                    try:
                        loop = asyncio.get_running_loop()
                    except RuntimeError:
                        loop = None
                completion = loop.create_future() if loop is not None and loop.is_running() else None
                if completion is not None:
                    self._proactive_completion_futures[sentence_id] = completion
            finally:
                self._proactive_state_lock.release()
        finally:
            if state_gate is not None:
                state_gate.release()
        self.logger.bind(tag=TAG).info(
            f"主动陪伴准备播放: chars={len(plan.text)}, reason={plan.reason_code}"
        )
        try:
            await send_tts_message(self, "start")
            self.tts.store_tts_text(sentence_id, plan.text)
            self.tts.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=sentence_id,
                    sentence_type=SentenceType.FIRST,
                    content_type=ContentType.ACTION,
                )
            )
            result = self.tts.tts_one_sentence(
                self,
                ContentType.TEXT,
                content_detail=plan.text,
                sentence_id=sentence_id,
            )
            if inspect.isawaitable(result):
                await result
            self.tts.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=sentence_id,
                    sentence_type=SentenceType.LAST,
                    content_type=ContentType.ACTION,
                )
            )
            if completion is not None:
                return bool(await completion)
            return True
        except asyncio.CancelledError:
            self.cancel_proactive_playback()
            raise
        except Exception:
            self.cancel_proactive_playback()
            self.emit_debug_event(
                "conversation",
                "conversation.proactive_failed",
                "error",
                "主动陪伴播放失败",
                details={"errorClass": "TTS"},
                sentence_id=sentence_id,
            )
            raise
        finally:
            if not self.proactive_playback_active and self._proactive_sentence_id == sentence_id:
                self._proactive_sentence_id = None

    def _finalize_proactive_sentence(self, sentence_id, success=True, error=None):
        """Commit proactive text only after TTS reaches a terminal state."""
        self._ensure_proactive_state()
        if not sentence_id:
            return False
        state_gate = getattr(self, "companion_mutex", None)
        gate_acquired = state_gate.acquire(False) if state_gate is not None else True
        if not gate_acquired:
            with self._proactive_state_lock:
                self._pending_proactive_messages.pop(sentence_id, None)
                if getattr(self, "_proactive_sentence_id", None) == sentence_id:
                    self._proactive_sentence_id = None
                    self.proactive_playback_active = False
                    self._proactive_audio_started = False
                    self.client_is_speaking = False
            self._resolve_proactive_completion(sentence_id, False)
            return False
        try:
            with self._proactive_state_lock:
                plan = self._pending_proactive_messages.pop(sentence_id, None)
                stop_requested = self._closed or (
                    getattr(self, "stop_event", None) is not None and self.stop_event.is_set()
                )
                chat_active = bool(getattr(self, "chat_in_progress", False))
                should_drop = plan is None or stop_requested or chat_active
                if not should_drop and success:
                    self.dialogue.put(
                        Message(role="assistant", content=plan.text, source="proactive")
                    )
                    self._proactive_history.append(plan.text)
        finally:
            if state_gate is not None:
                state_gate.release()
        if should_drop:
            self._resolve_proactive_completion(sentence_id, False)
            return False
        if not success:
            if getattr(self, "_proactive_sentence_id", None) == sentence_id:
                self.cancel_proactive_playback()
            else:
                self.proactive_playback_active = False
            self.emit_debug_event(
                "conversation",
                "conversation.proactive_failed",
                "error",
                "主动陪伴播放失败",
                details={"errorClass": type(error).__name__ if error else "TTS"},
                sentence_id=sentence_id,
            )
            self._resolve_proactive_completion(sentence_id, False)
            return False

        self.emit_debug_event(
            "conversation",
            "conversation.proactive",
            "info",
            "主动陪伴消息已生成",
            details={
                "source": "proactive",
                "textLength": len(plan.text),
                "reasonCode": plan.reason_code,
                "memoryIds": list(getattr(plan, "memory_ids", ()) or ()),
            },
            sentence_id=sentence_id,
        )
        memory_task = self._save_proactive_memory(plan, sentence_id)
        if inspect.isawaitable(memory_task):
            loop = getattr(self, "loop", None)
            if loop is None:
                try:
                    loop = asyncio.get_running_loop()
                except RuntimeError:
                    loop = None
            if loop is not None and loop.is_running():
                with self._proactive_state_lock:
                    stop_event = getattr(self, "stop_event", None)
                    stopped = self._closed or (
                        stop_event is not None and stop_event.is_set()
                    )
                    if not stopped:
                        task = loop.create_task(memory_task)
                        self._proactive_memory_tasks.add(task)
                        task.add_done_callback(self._proactive_memory_tasks.discard)
                    else:
                        memory_task.close()
            else:
                memory_task.close()
        self._resolve_proactive_completion(sentence_id, True)
        return True

    def _resolve_proactive_completion(self, sentence_id, result):
        self._ensure_proactive_state()
        with self._proactive_state_lock:
            future = self._proactive_completion_futures.pop(sentence_id, None)
        if future is None or future.done():
            return

        def resolve():
            if not future.done():
                future.set_result(result)

        loop = getattr(self, "loop", None)
        try:
            current_loop = asyncio.get_running_loop()
        except RuntimeError:
            current_loop = None
        if loop is not None and loop.is_running() and loop is not current_loop:
            try:
                loop.call_soon_threadsafe(resolve)
            except RuntimeError:
                # The connection loop can close between the state snapshot
                # and this callback. close() has already resolved/cleared the
                # future in the normal path, so there is nothing left to do.
                return
        else:
            resolve()

    def _on_proactive_tts_terminal(self, sentence_id, success=True, error=None):
        """Marshal TTS worker callbacks onto the connection event loop."""
        self._ensure_proactive_state()
        if self._closed:
            return False
        loop = getattr(self, "loop", None)
        try:
            current_loop = asyncio.get_running_loop()
        except RuntimeError:
            current_loop = None
        if loop is None or not loop.is_running() or loop is current_loop:
            self._finalize_proactive_sentence(sentence_id, success, error)
        else:
            try:
                loop.call_soon_threadsafe(
                    self._finalize_proactive_sentence, sentence_id, success, error
                )
            except RuntimeError:
                self._finalize_proactive_sentence(sentence_id, success, error)

    async def _save_proactive_memory(self, plan, sentence_id):
        memory = getattr(self, "memory", None)
        if memory is None or self._memory_flag_is_disabled(
            self.config.get("memory_enabled")
        ):
            return False
        if self._memory_debug_skip_reason() is not None:
            return False
        metadata = {
            "source": "proactive",
            "memory_ids": list(getattr(plan, "memory_ids", ()) or ()),
            "source_device_id": getattr(getattr(self, "companion_identity", None), "device_id", None),
            "source_profile_id": getattr(getattr(self, "companion_identity", None), "agent_id", None),
            "proactive_at": int(time.time()),
        }
        try:
            add_item = getattr(memory, "add_memory_item", None)
            if callable(add_item):
                try:
                    result = add_item(plan.text, source_metadata=metadata)
                    if inspect.isawaitable(result):
                        result = await asyncio.wait_for(result, timeout=5)
                    if self._memory_save_succeeded(result):
                        self._proactive_memory_saved_ids.add(sentence_id)
                        return True
                except (NotImplementedError, AttributeError):
                    pass
            save_memory = getattr(memory, "save_memory", None)
            if not callable(save_memory):
                return False
            recent_user = next(
                (
                    item
                    for item in reversed(self.dialogue.dialogue[:-1])
                    if item.role == "user" and not item.is_temporary
                ),
                None,
            )
            messages = ([recent_user] if recent_user is not None else []) + [
                Message(role="assistant", content=plan.text, source="proactive")
            ]
            result = save_memory(messages, session_id=sentence_id)
            if inspect.isawaitable(result):
                result = await asyncio.wait_for(result, timeout=5)
            success = self._memory_save_succeeded(result)
            if success:
                self._proactive_memory_saved_ids.add(sentence_id)
            return success
        except Exception as error:
            self.logger.bind(tag=TAG).debug(
                f"主动陪伴记忆保存失败: {type(error).__name__}"
            )
            return False

    @staticmethod
    def _memory_save_succeeded(result):
        """Only treat an explicit provider success as a saved memory."""
        if result is True:
            return True
        if isinstance(result, dict):
            return result.get("success") is True or bool(
                result.get("id") or result.get("memory_id")
            )
        if isinstance(result, str):
            return bool(result.strip())
        return False

    def cancel_proactive_playback(self):
        """Stop only proactive audio and invalidate queued packets."""
        self._ensure_proactive_state()
        if not getattr(self, "proactive_playback_active", False):
            return False
        self.client_abort = True
        sentence_id = getattr(self, "_proactive_sentence_id", None)
        self.proactive_playback_active = False
        if sentence_id:
            with self._proactive_state_lock:
                self._pending_proactive_messages.pop(sentence_id, None)
            self._resolve_proactive_completion(sentence_id, False)
        self._proactive_audio_started = False
        if getattr(self, "audio_rate_controller", None) is not None:
            self.audio_rate_controller.stop_sending()
            self.audio_rate_controller.reset()
        if self.tts is not None:
            for name in ("tts_text_queue", "tts_audio_queue"):
                queue_object = getattr(self.tts, name, None)
                if queue_object is None:
                    continue
                while True:
                    try:
                        queue_object.get_nowait()
                    except queue.Empty:
                        break
        self.client_is_speaking = False
        websocket = getattr(self, "websocket", None)
        loop = getattr(self, "loop", None)
        if websocket is not None and loop is not None and loop.is_running():
            async def send_stop():
                try:
                    await websocket.send(
                        json.dumps(
                            {
                                "type": "tts",
                                "state": "stop",
                                "session_id": self.session_id,
                            }
                        )
                    )
                except Exception:
                    pass
            loop.call_soon_threadsafe(lambda: asyncio.create_task(send_stop()))
        self._proactive_sentence_id = None
        self.emit_debug_event(
            "audio",
            "conversation.proactive_cancelled",
            "info",
            "主动陪伴播放已取消",
            details={"reason": "user_activity"},
            sentence_id=sentence_id,
        )
        return True

    def _ensure_proactive_state(self):
        if not hasattr(self, "_proactive_state_lock"):
            self._proactive_state_lock = threading.Lock()
        if not hasattr(self, "_pending_proactive_messages"):
            self._pending_proactive_messages = {}
        if not hasattr(self, "_proactive_memory_saved_ids"):
            self._proactive_memory_saved_ids = set()
        if not hasattr(self, "_proactive_memory_tasks"):
            self._proactive_memory_tasks = set()
        if not hasattr(self, "_proactive_audio_started"):
            self._proactive_audio_started = False
        if not hasattr(self, "_proactive_completion_futures"):
            self._proactive_completion_futures = {}
        if not hasattr(self, "_closed"):
            self._closed = False

    def notify_confirmed_user_activity(self):
        self.last_confirmed_user_activity = time.monotonic()
        if self._companion_loop is not None:
            self._companion_loop.notify_activity()
        if self.client_is_speaking and getattr(self, "_proactive_sentence_id", None):
            self.cancel_proactive_playback()

    def confirm_audio_activity(self, pcm_frame, vad_voice):
        sample_rate = 16000
        try:
            samples = len(pcm_frame) // 2
            frame_duration_ms = samples * 1000.0 / sample_rate
        except (TypeError, ValueError):
            frame_duration_ms = None
        return self._activity_gate.confirm(pcm_frame, vad_voice, frame_duration_ms)

    def _configure_activity_gate(self):
        companion = self.config.get("companion", {})
        if not isinstance(companion, dict):
            return
        defaults = {
            "activity_min_rms": 160.0,
            "activity_noise_ratio": 2.0,
            "activity_min_active_frames": 1,
            "activity_min_active_ms": 0,
        }
        values = {}
        for key, default in defaults.items():
            raw = companion.get(key, default)
            try:
                values[key] = float(raw) if isinstance(default, float) else int(raw)
            except (TypeError, ValueError):
                values[key] = default
        self._activity_gate = AudioActivityGate(
            min_rms=values["activity_min_rms"],
            noise_ratio=values["activity_noise_ratio"],
            min_active_frames=values["activity_min_active_frames"],
            min_active_ms=values["activity_min_active_ms"],
        )

    def _inject_tool_call_fewshot(self):
        """注入工具调用 few-shot 示例到对话历史。
        结构：正样本（工具调用示例）放在动态 system 之前，可命中前缀缓存；
        负样本（直接回答示例）放在动态 system 之后、紧挨真实用户消息，
        确保模型在处理用户消息前最后看到的是"不调工具"的行为模式。
        """
        if self.intent_type != "function_call":
            return
        if not hasattr(self, "func_handler") or self.func_handler is None:
            return

        tools = self.func_handler.get_functions()
        if not tools:
            return

        tool_names = {t.get("function", {}).get("name") for t in tools}

        # === few-shot 示例（is_temporary）===
        # 展示 direct_answer 携带 response 参数的用法，一次调用完成回复

        # 示例1：direct_answer（回复内容写在 response 参数里，无需递归）
        da_tc_id = "fewshot_da_001"
        self.dialogue.put(Message(role="user", content="给我讲个故事吧", is_temporary=True))
        self.dialogue.put(Message(
            role="assistant",
            tool_calls=[{
                "id": da_tc_id,
                "function": {"arguments": '{"response": "好呀，你想听什么类型的呀？童话、冒险还是搞笑的？选一个我给你开讲~"}', "name": "direct_answer"},
                "type": "function", "index": 0,
            }],
            is_temporary=True,
        ))
        self.dialogue.put(Message(
            role="tool", tool_call_id=da_tc_id,
            content="已直接回复", is_temporary=True,
        ))

        # 示例2：真实工具调用（handle_exit_intent）
        if "handle_exit_intent" in tool_names:
            tc_id = "fewshot_exit_001"
            self.dialogue.put(Message(role="user", content="拜拜", is_temporary=True))
            self.dialogue.put(Message(
                role="assistant",
                tool_calls=[{
                    "id": tc_id,
                    "function": {"arguments": '{"say_goodbye": "再见，下次再聊~"}', "name": "handle_exit_intent"},
                    "type": "function", "index": 0,
                }],
                is_temporary=True,
            ))
            self.dialogue.put(Message(
                role="tool", tool_call_id=tc_id,
                content="退出意图已处理", is_temporary=True,
            ))
            self.dialogue.put(Message(
                role="assistant", content="再见，下次再聊~", is_temporary=True,
            ))

        self.logger.bind(tag=TAG).debug("已注入工具调用 few-shot 示例")

    def _init_report_threads(self):
        """初始化ASR和TTS上报线程"""
        if not self.read_config_from_api or self.need_bind:
            return
        if self.chat_history_conf == 0:
            return
        if self.report_thread is None or not self.report_thread.is_alive():
            self.report_thread = threading.Thread(
                target=self._report_worker, daemon=True
            )
            self.report_thread.start()
            self.logger.bind(tag=TAG).info("TTS上报线程已启动")

    def _initialize_tts(self):
        """初始化TTS"""
        tts = None
        if not self.need_bind:
            tts = initialize_tts(self.config)

        if tts is None:
            tts = DefaultTTS(self.config, delete_audio_file=True)

        return tts

    def _initialize_asr(self):
        """初始化ASR"""
        if (
                self._asr is not None
                and hasattr(self._asr, "interface_type")
                and self._asr.interface_type == InterfaceType.LOCAL
        ):
            # 如果公共ASR是本地服务，则直接返回
            # 因为本地一个实例ASR，可以被多个连接共享
            asr = self._asr
        else:
            # 如果公共ASR是远程服务，则初始化一个新实例
            # 因为远程ASR，涉及到websocket连接和接收线程，需要每个连接一个实例
            asr = initialize_asr(self.config)

        return asr

    def _initialize_voiceprint(self):
        """为当前连接初始化声纹识别"""
        try:
            voiceprint_config = self.config.get("voiceprint", {})
            if voiceprint_config:
                voiceprint_provider = VoiceprintProvider(voiceprint_config)
                if voiceprint_provider is not None and voiceprint_provider.enabled:
                    self.voiceprint_provider = voiceprint_provider
                    self.logger.bind(tag=TAG).info("声纹识别功能已在连接时动态启用")
                else:
                    self.logger.bind(tag=TAG).warning("声纹识别功能启用但配置不完整")
            else:
                self.logger.bind(tag=TAG).info("声纹识别功能未启用")
        except Exception as e:
            self.logger.bind(tag=TAG).warning(f"声纹识别初始化失败: {str(e)}")

    async def _background_initialize(self):
        """在后台初始化配置和组件（完全不阻塞主循环）"""
        try:
            if getattr(self, "_closed", False) or (
                getattr(self, "stop_event", None) is not None
                and self.stop_event.is_set()
            ):
                return
            # 异步获取差异化配置
            await self._initialize_private_config_async()
            if getattr(self, "_closed", False) or (
                getattr(self, "stop_event", None) is not None
                and self.stop_event.is_set()
            ):
                return
            # 在线程池中初始化组件
            self._component_init_future = self.executor.submit(self._initialize_components)
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"后台初始化失败: {e}")

    async def _initialize_private_config_async(self, *, refresh=False):
        """从接口异步获取差异化配置（异步版本，不阻塞主循环）"""
        if not self.read_config_from_api:
            self.companion_identity = CompanionIdentity.from_config(self.config)
            self.capability_bundle = None
            self.need_bind = False
            self.bind_completed_event.set()
            return
        try:
            begin_time = time.time()
            private_config = await get_private_config_from_api(
                self.config,
                self.headers.get("device-id"),
                self.headers.get("client-id", self.headers.get("device-id")),
            )
            private_config["delete_audio"] = bool(self.config.get("delete_audio", True))
            self.logger.bind(tag=TAG).info(
                f"{time.time() - begin_time} 秒，异步获取差异化配置成功: {json.dumps(filter_sensitive_info(private_config), ensure_ascii=False)}"
            )
            self.need_bind = False
            self.bind_completed_event.set()
        except DeviceNotFoundException as e:
            if refresh:
                self.logger.bind(tag=TAG).debug("刷新设备配置时设备暂不可用")
                return False
            self.need_bind = True
            private_config = {}
        except DeviceBindException as e:
            if refresh:
                self.logger.bind(tag=TAG).debug("刷新设备配置时设备绑定状态已变化")
                return False
            self.need_bind = True
            self.bind_code = e.bind_code
            private_config = {}
        except Exception as e:
            if refresh:
                self.logger.bind(tag=TAG).debug(
                    f"刷新设备配置失败: {type(e).__name__}"
                )
                return False
            self.need_bind = True
            self.logger.bind(tag=TAG).error(f"异步获取差异化配置失败: {e}")
            private_config = {}

        signature = self._private_config_signature_for(private_config)
        if (
            refresh
            and signature is not None
            and signature == self._private_config_signature
        ):
            return False

        self.companion_identity = CompanionIdentity.from_config(private_config)
        if not self.need_bind:
            await self._load_device_capability_bundle(force_refresh=True)
        self._apply_device_wakeup_words(private_config)
        if self.companion_identity is not None:
            self.config["companion_identity"] = private_config["companion_identity"]
            identity_memory_enabled = private_config["companion_identity"].get("memory_enabled")
            if identity_memory_enabled is not None:
                self.config["memory_enabled"] = identity_memory_enabled
            self._skill_runtime.set_role_metadata(
                {
                    "agentId": self.companion_identity.agent_id,
                    "deviceId": self.companion_identity.device_id,
                }
            )
        else:
            self.logger.bind(tag=TAG).warning(
                "陪伴身份缺失，当前连接禁用长期记忆"
            )

        private_companion = private_config.get("companion")
        if isinstance(private_companion, dict):
            local_companion = self.config.get("companion")
            merged_companion = (
                copy.deepcopy(local_companion)
                if isinstance(local_companion, dict)
                else {}
            )
            merged_companion.update(copy.deepcopy(private_companion))
            self.config["companion"] = merged_companion
            self._configure_activity_gate()

        init_llm, init_tts, init_memory, init_intent = (
            False,
            False,
            False,
            False,
        )

        init_vad = check_vad_update(self.common_config, private_config)
        init_asr = check_asr_update(self.common_config, private_config)

        if init_vad:
            self.config["VAD"] = private_config["VAD"]
            self.config["selected_module"]["VAD"] = private_config["selected_module"][
                "VAD"
            ]
        if init_asr:
            self.config["ASR"] = private_config["ASR"]
            self.config["selected_module"]["ASR"] = private_config["selected_module"][
                "ASR"
            ]
        if private_config.get("TTS", None) is not None:
            init_tts = True
            self.config["TTS"] = private_config["TTS"]
            self.config["selected_module"]["TTS"] = private_config["selected_module"][
                "TTS"
            ]
        if private_config.get("LLM", None) is not None:
            init_llm = True
            self.config["LLM"] = private_config["LLM"]
            self.config["selected_module"]["LLM"] = private_config["selected_module"][
                "LLM"
            ]
        if private_config.get("VLLM", None) is not None:
            self.config["VLLM"] = private_config["VLLM"]
            self.config["selected_module"]["VLLM"] = private_config["selected_module"][
                "VLLM"
            ]
        if private_config.get("Memory", None) is not None:
            init_memory = True
            self.config["Memory"] = private_config["Memory"]
            self.config["selected_module"]["Memory"] = private_config[
                "selected_module"
            ]["Memory"]
        if private_config.get("Intent", None) is not None:
            init_intent = True
            self.config["Intent"] = private_config["Intent"]
            model_intent = private_config.get("selected_module", {}).get("Intent", {})
            self.config["selected_module"]["Intent"] = model_intent
            # 加载插件配置
            if model_intent != "Intent_nointent":
                plugin_from_server = private_config.get("plugins", {})
                for plugin, config_str in plugin_from_server.items():
                    plugin_from_server[plugin] = json.loads(config_str)
                self.config["plugins"] = plugin_from_server
                self.config["Intent"][self.config["selected_module"]["Intent"]][
                    "functions"
                ] = plugin_from_server.keys()
        if private_config.get("prompt", None) is not None:
            self.config["prompt"] = private_config["prompt"]
        # 获取声纹信息
        if private_config.get("voiceprint", None) is not None:
            self.config["voiceprint"] = private_config["voiceprint"]
        if private_config.get("summaryMemory", None) is not None:
            self.config["summaryMemory"] = private_config["summaryMemory"]
        if private_config.get("device_max_output_size", None) is not None:
            self.max_output_size = int(private_config["device_max_output_size"])
        if private_config.get("chat_history_conf", None) is not None:
            self.chat_history_conf = int(private_config["chat_history_conf"])
        if private_config.get("mcp_endpoint", None) is not None:
            self.config["mcp_endpoint"] = private_config["mcp_endpoint"]
        if private_config.get("context_providers", None) is not None:
            self.config["context_providers"] = private_config["context_providers"]

        # 注入替换词到 TTS 模块配置
        if private_config.get("correct_words", None) is not None:
            select_tts_module = self.config["selected_module"]["TTS"]
            self.config["TTS"][select_tts_module]["correct_words"] = private_config[
                "correct_words"
            ]

        # 使用 run_in_executor 在线程池中执行 initialize_modules，避免阻塞主循环
        try:
            modules = await self.loop.run_in_executor(
                None,  # 使用默认线程池
                initialize_modules,
                self.logger,
                private_config,
                init_vad,
                init_asr,
                init_llm,
                init_tts,
                init_memory,
                init_intent,
            )
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"初始化组件失败: {e}")
            modules = {}
        old_modules = {
            "tts": self.tts,
            "asr": self.asr,
            "memory": self.memory,
        }
        if modules.get("tts", None) is not None:
            self.tts = modules["tts"]
        if modules.get("vad", None) is not None:
            self.vad = modules["vad"]
        if modules.get("asr", None) is not None:
            self.asr = modules["asr"]
        if modules.get("llm", None) is not None:
            self.llm = modules["llm"]
        if modules.get("intent", None) is not None:
            self.intent = modules["intent"]
        if modules.get("memory", None) is not None:
            self.memory = modules["memory"]
        if refresh:
            old_companion_loop = getattr(self, "_companion_loop", None)
            if old_companion_loop is not None:
                try:
                    await old_companion_loop.stop()
                except Exception as error:
                    self.logger.bind(tag=TAG).debug(
                        f"刷新角色时关闭旧主动陪伴循环失败: {type(error).__name__}"
                    )
                self._companion_loop = None
            old_func_handler = getattr(self, "func_handler", None)
            for name in ("tts", "asr"):
                current = getattr(self, name, None)
                if current is None or current is old_modules.get(name):
                    continue
                try:
                    await current.open_audio_channels(self)
                except Exception as error:
                    self.logger.bind(tag=TAG).debug(
                        f"刷新{name}音频通道失败: {type(error).__name__}"
                    )
            for name, old in old_modules.items():
                if old is None or old is getattr(self, name, None):
                    continue
                close = getattr(old, "close", None)
                if callable(close):
                    try:
                        result = close()
                        if inspect.isawaitable(result):
                            await result
                    except Exception as error:
                        self.logger.bind(tag=TAG).debug(
                            f"关闭旧{name}组件失败: {type(error).__name__}"
                        )
            self._initialize_memory()
            if init_intent:
                try:
                    self._initialize_intent()
                except Exception as error:
                    self.logger.bind(tag=TAG).debug(
                        f"刷新意图组件失败: {type(error).__name__}"
                    )
            if old_func_handler is not None and old_func_handler is not getattr(
                self, "func_handler", None
            ):
                cleanup = getattr(old_func_handler, "cleanup", None)
                if callable(cleanup):
                    try:
                        result = cleanup()
                        if inspect.isawaitable(result):
                            await result
                    except Exception as error:
                        self.logger.bind(tag=TAG).debug(
                            f"关闭旧意图组件失败: {type(error).__name__}"
                        )
            if self.config.get("prompt") is not None:
                self.change_system_prompt(self.config["prompt"])
            try:
                self._init_prompt_enhancement()
            except Exception as error:
                self.logger.bind(tag=TAG).debug(
                    f"刷新角色提示词失败: {type(error).__name__}"
                )
            if not getattr(self, "_closed", False) and not (
                getattr(self, "stop_event", None) is not None
                and self.stop_event.is_set()
            ):
                self._start_companion_loop()
        self._private_config_signature = signature
        return True

    @staticmethod
    def _private_config_signature_for(private_config):
        if not isinstance(private_config, dict):
            return None
        identity = private_config.get("companion_identity")
        if not isinstance(identity, dict):
            return None
        stable = {
            "user_id": identity.get("user_id"),
            "agent_id": identity.get("agent_id"),
            "device_id": identity.get("device_id"),
            "agent_version_no": private_config.get("agent_version_no")
            or private_config.get("agentVersionNo"),
            "selected_module": private_config.get("selected_module"),
            "LLM": private_config.get("LLM"),
            "TTS": private_config.get("TTS"),
            "Memory": private_config.get("Memory"),
            "VLLM": private_config.get("VLLM"),
            "Intent": private_config.get("Intent"),
            "prompt": private_config.get("prompt"),
            "companion": private_config.get("companion"),
            "voiceprint": private_config.get("voiceprint"),
            "correct_words": private_config.get("correct_words"),
            "summaryMemory": private_config.get("summaryMemory"),
            "memory_enabled": private_config.get("memory_enabled"),
        }
        try:
            encoded = json.dumps(
                stable, sort_keys=True, ensure_ascii=False, default=str
            )
        except (TypeError, ValueError):
            return None
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    def _refresh_private_config_for_turn(self):
        """Refresh the active device profile at a turn boundary.

        A running turn keeps its captured prompt and providers. The next turn
        can observe a profile switch made through manager-api.
        """
        if not getattr(self, "read_config_from_api", False) or not getattr(
            self, "device_id", None
        ):
            return False
        if getattr(self, "_closed", False) or (
            getattr(self, "stop_event", None) is not None
            and self.stop_event.is_set()
        ):
            return False
        coroutine = self._initialize_private_config_async(refresh=True)
        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None
        loop = getattr(self, "loop", None)
        if loop is not None and loop.is_running() and running_loop is not loop:
            future = asyncio.run_coroutine_threadsafe(coroutine, loop)
            try:
                return bool(future.result(timeout=10))
            except Exception:
                future.cancel()
                return False
        if running_loop is None:
            try:
                return bool(asyncio.run(coroutine))
            except Exception:
                return False
        # chat() should not synchronously block an event-loop caller.
        coroutine.close()
        return False

    def _apply_device_wakeup_words(self, private_config):
        device_words = private_config.get("device_wakeup_words")
        if not isinstance(device_words, list):
            return
        global_words = list(self.common_config.get("wakeup_words", []))
        normalized = [
            word.strip()
            for word in device_words
            if isinstance(word, str) and word.strip()
        ]
        self.config["wakeup_words"] = list(dict.fromkeys(global_words + normalized))

    def _initialize_memory(self):
        memory_enabled = self.config.get("memory_enabled")
        if memory_enabled is None:
            identity_config = self.config.get("companion_identity")
            if isinstance(identity_config, dict):
                memory_enabled = identity_config.get("memory_enabled")
                if memory_enabled is None:
                    memory_enabled = identity_config.get("memoryEnabled")
        if memory_enabled is None:
            companion_config = self.config.get("companion")
            if isinstance(companion_config, dict):
                memory_enabled = companion_config.get("memory_enabled")
                if memory_enabled is None:
                    memory_enabled = companion_config.get("memoryEnabled")
        if self._memory_flag_is_disabled(memory_enabled):
            self.memory = None
            return
        if self.memory is None:
            return
        """初始化记忆模块"""
        if self.companion_identity is None:
            self.logger.bind(tag=TAG).warning("无有效陪伴身份，跳过长期记忆初始化")
            self.memory = None
            return
        select_memory_module = self.config["selected_module"]["Memory"]
        memory_config = self.config["Memory"][select_memory_module]
        memory_type = memory_config.get("type", select_memory_module)
        summary_memory = self.config.get("summaryMemory", None)
        if is_profile_memory_namespace(self.companion_identity.memory_namespace):
            # The legacy Agent summary is shared state and must not seed the
            # user/profile namespace used by App and hardware connections.
            summary_memory = None
        self.memory = memory_utils.create_instance(
            memory_type,
            memory_config,
            summary_memory,
        )
        self.memory.init_memory(
            memory_namespace=self.companion_identity.memory_namespace,
            llm=self.llm,
            summary_memory=summary_memory,
            # Canonical profile memory is shared by App and every bound device,
            # so API-backed connections must use the same local namespace file
            # as the profile management handler. Legacy device namespaces keep
            # the existing API summary behavior.
            save_to_file=(
                is_profile_memory_namespace(self.companion_identity.memory_namespace)
                or not self.read_config_from_api
            ),
            source_metadata={
                "source_user_id": self.companion_identity.user_id,
                "source_device_id": self.companion_identity.device_id,
                "source_profile_id": self.companion_identity.agent_id,
            },
        )

        # 获取记忆总结配置
        memory_config = self.config["Memory"]
        memory_type = self.config["Memory"][self.config["selected_module"]["Memory"]][
            "type"
        ]
        # 如果使用 nomen 或 mem_report_only，直接返回
        if memory_type == "nomem" or memory_type == "mem_report_only":
            return
        # 使用 mem_local_short 模式
        elif memory_type == "mem_local_short":
            memory_llm_name = memory_config[
                self.config["selected_module"]["Memory"]
            ].get("llm")
            llm_configs = self.config.get("LLM") or {}
            if memory_llm_name and memory_llm_name in llm_configs:
                # 如果配置了专用LLM，则创建独立的LLM实例
                from core.utils import llm as llm_utils

                memory_llm_config = llm_configs[memory_llm_name]
                memory_llm_type = memory_llm_config.get("type", memory_llm_name)
                memory_llm = llm_utils.create_instance(
                    memory_llm_type, memory_llm_config
                )
                self.logger.bind(tag=TAG).info(
                    f"为记忆总结创建了专用LLM: {memory_llm_name}, 类型: {memory_llm_type}"
                )
                self.memory.set_llm(memory_llm)
            else:
                # 否则使用主LLM
                self.memory.set_llm(self.llm)
                self.logger.bind(tag=TAG).info("使用主LLM作为意图识别模型")

    def _initialize_intent(self):
        if self.intent is None:
            return
        self.intent_type = self.config["Intent"][
            self.config["selected_module"]["Intent"]
        ]["type"]
        if self.intent_type == "function_call" or self.intent_type == "intent_llm":
            self.load_function_plugin = True
        """初始化意图识别模块"""
        # 获取意图识别配置
        intent_config = self.config["Intent"]
        intent_type = self.config["Intent"][self.config["selected_module"]["Intent"]][
            "type"
        ]

        # 如果使用 nointent，直接返回
        if intent_type == "nointent":
            return
        # 使用 intent_llm 模式
        elif intent_type == "intent_llm":
            intent_llm_name = intent_config[self.config["selected_module"]["Intent"]][
                "llm"
            ]

            if intent_llm_name and intent_llm_name in self.config["LLM"]:
                # 如果配置了专用LLM，则创建独立的LLM实例
                from core.utils import llm as llm_utils

                intent_llm_config = self.config["LLM"][intent_llm_name]
                intent_llm_type = intent_llm_config.get("type", intent_llm_name)
                intent_llm = llm_utils.create_instance(
                    intent_llm_type, intent_llm_config
                )

                self.logger.bind(tag=TAG).info(
                    f"为意图识别创建了专用LLM: {intent_llm_name}, 类型: {intent_llm_type}"
                )
                self.intent.set_llm(intent_llm)
            else:
                # 否则使用主LLM
                self.intent.set_llm(self.llm)
                self.logger.bind(tag=TAG).info("使用主LLM作为意图识别模型")

        """加载统一工具处理器"""
        self.func_handler = UnifiedToolHandler(self)

        # 异步初始化工具处理器
        if hasattr(self, "loop") and self.loop:
            asyncio.run_coroutine_threadsafe(self.func_handler._initialize(), self.loop)

    async def _load_device_capability_bundle(self, *, force_refresh=False):
        if not self.read_config_from_api or not self.device_id:
            self._set_capability_bundle(None)
            return None
        bundle = await _CAPABILITY_BUNDLE_CACHE.get(
            self.device_id, force_refresh=force_refresh
        )
        self._set_capability_bundle(bundle)
        if self._connection_capability_bundle is None or force_refresh:
            self._connection_capability_bundle = bundle
        return bundle

    def _set_capability_bundle(self, bundle):
        previous = getattr(self, "capability_bundle", None)
        self.capability_bundle = bundle
        if (
            previous is not bundle
            and hasattr(self, "func_handler")
            and self.func_handler is not None
        ):
            self.func_handler.tool_manager.refresh_tools()

    def _refresh_capability_bundle_for_turn(self):
        bundle = getattr(self, "capability_bundle", None)
        if not self.read_config_from_api or not self.device_id:
            return bundle
        connection_bundle = getattr(self, "_connection_capability_bundle", None)
        if connection_bundle is not None:
            return connection_bundle
        loop = getattr(self, "loop", None)
        if loop is None or not loop.is_running():
            return bundle
        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None
        if running_loop is loop:
            return bundle
        future = asyncio.run_coroutine_threadsafe(
            _CAPABILITY_BUNDLE_CACHE.get(self.device_id, force_refresh=True), loop
        )
        try:
            refreshed = future.result(timeout=5)
        except Exception:
            future.cancel()
            return bundle
        self._set_capability_bundle(refreshed)
        return refreshed

    def _begin_skill_turn(self, query):
        self._skill_query = query or ""
        self._skill_turn = None
        self._skill_turn_started_at = time.monotonic()
        self._skill_result_class = "NO_TOOL"
        self._skill_turn_finished = False
        bundle = self._refresh_capability_bundle_for_turn()
        self._turn_capability_bundle = bundle
        tools_enabled = getattr(getattr(self, "llm", None), "tools_enabled", True)
        classifier = SkillClassifier(self.llm) if bundle is not None else None
        coroutine = self._skill_runtime.select(
            bundle,
            query or "",
            classifier,
            tools_enabled=tools_enabled,
        )
        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None
        future = None
        route_failed = False
        try:
            if self.loop is not None and self.loop.is_running() and running_loop is not self.loop:
                future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
                turn = future.result(timeout=4)
            elif running_loop is None:
                turn = asyncio.run(coroutine)
            else:
                coroutine.close()
                turn = None
        except Exception as error:
            route_failed = True
            if future is not None:
                future.cancel()
            else:
                coroutine.close()
            turn = None
            self.emit_debug_event(
                "model_tool",
                "skill.failed",
                "error",
                "Skill 路由失败",
                details={"resultClass": type(error).__name__},
                sentence_id=self.sentence_id,
                duration_ms=self._skill_duration_ms(),
            )
        self._skill_turn = turn
        if route_failed:
            return
        if turn is None or turn.skill is None:
            self.emit_debug_event(
                "model_tool",
                "skill.not_matched",
                "info",
                "本轮未匹配 Skill",
                details={"triggerMode": getattr(turn, "trigger_mode", "NONE")},
                sentence_id=self.sentence_id,
                duration_ms=self._skill_duration_ms(),
            )
            return
        self.emit_debug_event(
            "model_tool",
            "skill.matched",
            "info",
            "本轮已匹配 Skill",
            details=self._skill_event_details(turn.skill, turn.trigger_mode),
            sentence_id=self.sentence_id,
            duration_ms=self._skill_duration_ms(),
        )

    def _skill_event_details(self, skill, trigger_mode=None, result_class=None):
        details = {
            "skillId": skill.id,
            "publishedVersion": skill.version,
            "triggerMode": trigger_mode or getattr(self._skill_turn, "trigger_mode", "NONE"),
            "toolNames": list(skill.tool_names),
        }
        if result_class is not None:
            details["resultClass"] = result_class
        return details

    def _skill_duration_ms(self):
        started = self._skill_turn_started_at
        if started is None:
            return 0
        return max(0, int((time.monotonic() - started) * 1000))

    def _skill_tool_timeout_seconds(self):
        active_skill = getattr(getattr(self, "_skill_turn", None), "skill", None)
        if active_skill is not None:
            timeout_ms = getattr(active_skill, "timeout_ms", None)
            if isinstance(timeout_ms, int) and not isinstance(timeout_ms, bool) and timeout_ms > 0:
                return timeout_ms / 1000
        return float(self.config.get("tool_call_timeout", 30))

    def _apply_skill_response_policy(self, result):
        active_skill = getattr(getattr(self, "_skill_turn", None), "skill", None)
        if (
            active_skill is not None
            and getattr(active_skill, "response_mode", None) == "FIXED"
            and getattr(result, "action", None) == Action.REQLLM
        ):
            result.action = Action.RESPONSE
            result.response = result.response or result.result
        return result

    def _finish_skill_turn(self, error=None):
        turn = getattr(self, "_skill_turn", None)
        if self._skill_turn_finished or turn is None or turn.skill is None:
            return
        self._skill_turn_finished = True
        failed = error is not None or self._skill_result_class in {"ERROR", "NOTFOUND"}
        result_class = type(error).__name__ if error is not None else self._skill_result_class
        self.emit_debug_event(
            "model_tool",
            "skill.failed" if failed else "skill.completed",
            "error" if failed else "info",
            "Skill 执行失败" if failed else "Skill 执行完成",
            details=self._skill_event_details(turn.skill, result_class=result_class),
            sentence_id=self.sentence_id,
            duration_ms=self._skill_duration_ms(),
        )

    def _select_functions_for_query(self, query):
        """按当前设备 Skill 授权选择本轮可见工具。"""
        llm = getattr(self, "llm", None)
        if llm is not None and not getattr(llm, "tools_enabled", True):
            return []

        skill_turn = getattr(self, "_skill_turn", None)
        if skill_turn is not None:
            functions = list(self.func_handler.get_functions())
            if (
                getattr(self, "read_config_from_api", False)
                and hasattr(skill_turn, "skill")
                and skill_turn.skill is None
            ):
                device_tool_keywords = (
                    (
                        ("拍照", "照片", "相机", "摄像头", "画面", "看看"),
                        ("self_camera_take_photo",),
                    ),
                    (
                        ("音量", "声音"),
                        ("self_get_device_status", "self_audio_speaker_set_volume"),
                    ),
                    (
                        ("亮度",),
                        ("self_get_device_status", "self_screen_set_brightness"),
                    ),
                    (
                        ("屏幕", "主题"),
                        ("self_get_device_status", "self_screen_set_theme"),
                    ),
                    (("设备状态", "电量"), ("self_get_device_status",)),
                )
                text = query or ""
                allowed = {"handle_exit_intent"}
                for keywords, tool_names in device_tool_keywords:
                    if any(keyword in text for keyword in keywords):
                        allowed.update(tool_names)
                # Keep the executor authorization in sync with the tools exposed
                # for this intent. Without this, the model sees a device tool but
                # the second authorization check rejects its call.
                object.__setattr__(
                    skill_turn,
                    "allowed_tool_names",
                    frozenset(allowed),
                )
                return [
                    function
                    for function in functions
                    if function.get("function", {}).get("name") in allowed
                ]
            return list(
                self.func_handler.get_functions(skill_turn.allowed_tool_names)
            )

        safe_names = {"handle_exit_intent"}
        if getattr(self, "read_config_from_api", False):
            return list(self.func_handler.get_functions(safe_names))

        functions = list(self.func_handler.get_functions())
        if self.config.get("tools_for_chat", False):
            return functions

        text = query or ""
        try:
            if text.strip().startswith("{") and text.strip().endswith("}"):
                parsed = json.loads(text)
                if isinstance(parsed, dict):
                    text = str(parsed.get("content", text))
        except (json.JSONDecodeError, TypeError):
            pass

        tool_keywords = (
            "音量", "声音", "亮度", "屏幕", "主题", "拍照", "照片", "相机",
            "设备状态", "电量", "农历", "日期", "时间",
        )
        if any(keyword in text for keyword in tool_keywords):
            return functions

        return [
            function
            for function in functions
            if function.get("function", {}).get("name") in safe_names
        ]

    def _skill_tool_choice(self, depth):
        if depth != 0:
            return None
        turn = getattr(self, "_skill_turn", None)
        skill = getattr(turn, "skill", None)
        bundle = getattr(turn, "bundle", None)
        if skill is None or bundle is None:
            return None
        required_names = [
            name
            for name in skill.tool_names
            if (tool := bundle.tools.get(name)) is not None and tool.required
        ]
        if len(required_names) == 1:
            return {
                "type": "function",
                "function": {"name": required_names[0]},
            }
        return "required" if required_names else None

    def _prepare_model_functions(self, query, depth, force_final_answer):
        if (
            self.intent_type != "function_call"
            or not hasattr(self, "func_handler")
            or force_final_answer
        ):
            return None, None

        functions = self._select_functions_for_query(query) or None
        if functions is None:
            return None, None

        tool_choice = self._skill_tool_choice(depth)
        if tool_choice is not None:
            skill = getattr(getattr(self, "_skill_turn", None), "skill", None)
            skill_names = set(getattr(skill, "tool_names", ()))
            functions = [
                function
                for function in functions
                if function.get("function", {}).get("name") in skill_names
            ]
        elif depth == 0:
            functions.append(DIRECT_ANSWER_TOOL)

        return (functions or None), (tool_choice if functions else None)

    def change_system_prompt(self, prompt):
        self.prompt = prompt
        # 更新系统prompt至上下文
        self.dialogue.update_system_message(self.prompt)

    def emit_debug_event(
        self,
        category,
        event_type,
        level,
        summary,
        *,
        details=None,
        sentence_id=None,
        duration_ms=None,
    ):
        if self.debug_events is None:
            return False
        try:
            return self.debug_events.emit(
                category,
                event_type,
                level,
                summary,
                details=details,
                sentence_id=sentence_id,
                duration_ms=duration_ms,
            )
        except Exception as error:
            self.logger.bind(tag=TAG).debug(
                f"调试事件发送失败: {type(error).__name__}"
            )
            return False

    def _memory_debug_skip_reason(self):
        if self.memory is None:
            return "memory_disabled"
        memory_enabled = self.config.get("memory_enabled")
        if memory_enabled is None:
            identity_config = self.config.get("companion_identity")
            if isinstance(identity_config, dict):
                memory_enabled = identity_config.get("memory_enabled")
                if memory_enabled is None:
                    memory_enabled = identity_config.get("memoryEnabled")
        if memory_enabled is None:
            companion_config = self.config.get("companion")
            if isinstance(companion_config, dict):
                memory_enabled = companion_config.get("memory_enabled")
                if memory_enabled is None:
                    memory_enabled = companion_config.get("memoryEnabled")
        if self._memory_flag_is_disabled(memory_enabled):
            return "memory_disabled"
        selected_module = self.config.get("selected_module")
        selected = selected_module.get("Memory") if isinstance(selected_module, dict) else None
        memory_modules = self.config.get("Memory")
        memory_config = memory_modules.get(selected, {}) if isinstance(memory_modules, dict) else {}
        memory_type = (
            memory_config.get("type", selected)
            if isinstance(memory_config, dict)
            else selected
        )
        if str(memory_type or "").strip().lower() in {
            "nomem", "memory_nomem", "mem_report_only", "memory_mem_report_only"
        }:
            return "memory_disabled"
        return None

    @staticmethod
    def _memory_flag_is_disabled(value):
        if value is None:
            return False
        if isinstance(value, bool):
            return not value
        if isinstance(value, (int, float)):
            return value == 0
        return str(value).strip().lower() in {"false", "0", "off", "no"}

    def _emit_llm_first_visible(self, sentence_id, text):
        if not sentence_id or not text or not str(text).strip():
            return False
        with self._debug_lifecycle_lock:
            if sentence_id in self._debug_llm_first_visible:
                return False
            started_state = self._debug_llm_started_at.get(sentence_id)
            if started_state is None:
                return False
            self._debug_llm_first_visible.add(sentence_id)
        if isinstance(started_state, tuple):
            started_at, started_ready = started_state
        else:
            started_at, started_ready = started_state, None
        if started_ready is not None:
            started_ready.wait()
        return self.emit_debug_event(
            "model_tool",
            "llm.first_visible",
            "info",
            "模型返回首段可播放文字",
            details={"textLength": len(str(text).strip())},
            sentence_id=sentence_id,
            duration_ms=max(0, int((time.monotonic() - started_at) * 1000)),
        )

    def _finish_llm_debug(self, sentence_id, text):
        with self._debug_lifecycle_lock:
            if sentence_id in self._debug_llm_finished:
                return False
            if not text:
                started_state = self._debug_llm_started_at.pop(sentence_id, None)
                self._debug_llm_first_visible.discard(sentence_id)
                if started_state is not None:
                    self._remember_finished_llm(sentence_id)
                return False
            started_state = self._debug_llm_started_at.pop(sentence_id, None)
            self._debug_llm_first_visible.discard(sentence_id)
            if started_state is None:
                return False
            self._remember_finished_llm(sentence_id)
        started_at, started_ready = self._unpack_debug_start(started_state)
        if started_ready is not None:
            started_ready.wait()
        duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
        self.emit_debug_event(
            "model_tool",
            "llm.completed",
            "info",
            "模型回复已完成",
            details={"outputLength": len(text), "text": text},
            sentence_id=sentence_id,
            duration_ms=duration_ms,
        )
        self.emit_debug_event(
            "conversation",
            "conversation.assistant",
            "info",
            "助手回复",
            details={"text": text},
            sentence_id=sentence_id,
        )
        return True

    def _remember_finished_llm(self, sentence_id):
        self._debug_llm_finished[sentence_id] = None
        self._debug_llm_finished.move_to_end(sentence_id)
        while len(self._debug_llm_finished) > 128:
            self._debug_llm_finished.popitem(last=False)

    @staticmethod
    def _unpack_debug_start(started_state):
        if isinstance(started_state, tuple):
            return started_state
        return started_state, None

    def _fail_llm_debug(self, sentence_id, error, error_class=None):
        with self._debug_lifecycle_lock:
            if sentence_id in self._debug_llm_finished:
                return False
            started_state = self._debug_llm_started_at.pop(sentence_id, None)
            self._debug_llm_first_visible.discard(sentence_id)
            if started_state is None:
                return False
            self._remember_finished_llm(sentence_id)
        started_at, started_ready = self._unpack_debug_start(started_state)
        if started_ready is not None:
            started_ready.wait()
        duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
        return self.emit_debug_event(
            "model_tool",
            "llm.failed",
            "error",
            "模型回复失败",
            details={"errorClass": error_class or type(error).__name__},
            sentence_id=sentence_id,
            duration_ms=duration_ms,
        )

    def _get_companion_provider_lock(self):
        """Return the one provider gate shared by chat and proactive planning."""
        provider_lock = getattr(self, "_companion_provider_lock", None)
        if provider_lock is None:
            provider_lock = getattr(self, "companion_provider_gate", None)
        if provider_lock is None:
            provider_lock = threading.Lock()
        self._companion_provider_lock = provider_lock
        self.companion_provider_gate = provider_lock
        return provider_lock

    def _companion_provider_wait_timeout(self):
        """Keep a stalled proactive provider from freezing a user turn."""
        config = getattr(self, "config", {}) or {}
        companion = config.get("companion", {}) if isinstance(config, dict) else {}
        if not isinstance(companion, dict):
            companion = {}
        raw_timeout = companion.get("chat_provider_wait_timeout_seconds")
        if raw_timeout is None:
            raw_timeout = companion.get("planner_timeout_seconds", 15.0)
        try:
            # A user turn may briefly wait for a cancellable planner, but must
            # fail fast when the provider ignores cancellation altogether.
            return min(0.2, max(0.01, float(raw_timeout)))
        except (TypeError, ValueError):
            return 0.2

    def _companion_chat_queue_timeout(self):
        """Bound how long a user turn may wait behind a stuck planner."""
        config = getattr(self, "config", {}) or {}
        companion = config.get("companion", {}) if isinstance(config, dict) else {}
        if not isinstance(companion, dict):
            companion = {}
        raw_timeout = companion.get("chat_queue_timeout_seconds", 30.0)
        try:
            return min(300.0, max(0.2, float(raw_timeout)))
        except (TypeError, ValueError):
            return 30.0

    def _abort_chat_for_provider_busy(self, queued=False):
        """Release client-side speaking state when the provider gate is busy."""
        self.client_abort = True
        clear_speak_status = getattr(self, "clearSpeakStatus", None)
        if callable(clear_speak_status):
            clear_speak_status()
        emit_debug_event = getattr(self, "emit_debug_event", None)
        if callable(emit_debug_event):
            emit_debug_event(
                "conversation",
                "conversation.chat_provider_busy",
                "warning",
                "上一轮模型请求仍在处理，当前消息已排队" if queued else "上一轮模型请求仍在处理，请稍后重试",
                details={"reason": "provider_busy", "queued": bool(queued)},
            )
        websocket = getattr(self, "websocket", None)
        loop = getattr(self, "loop", None)
        if websocket is None or loop is None or not loop.is_running():
            return

        async def send_stop():
            try:
                await websocket.send(
                    json.dumps(
                        {
                            "type": "error",
                            "code": "provider_busy",
                            "message": (
                                "上一轮模型请求仍在处理，当前消息已排队"
                                if queued
                                else "上一轮模型请求仍在处理，请稍后重试"
                            ),
                            "retryable": True,
                        },
                        ensure_ascii=False,
                    )
                )
                await websocket.send(
                    json.dumps(
                        {
                            "type": "tts",
                            "state": "stop",
                            "session_id": getattr(self, "session_id", ""),
                        }
                    )
                )
            except Exception:
                pass

        loop.call_soon_threadsafe(lambda: asyncio.create_task(send_stop()))

    def _enqueue_companion_chat(self, query, depth):
        """Queue a user turn while an uncancellable planner owns the provider."""
        queue_lock = getattr(self, "_companion_chat_queue_lock", None)
        if queue_lock is None:
            queue_lock = threading.Lock()
            self._companion_chat_queue_lock = queue_lock
        pending_queue = getattr(self, "_companion_chat_queue", None)
        if pending_queue is None:
            pending_queue = deque()
            self._companion_chat_queue = pending_queue
        with queue_lock:
            if len(pending_queue) >= 8:
                return False
            pending_queue.append((query, depth, time.monotonic()))
            self._companion_chat_pending = True
        ConnectionHandler._start_companion_chat_dispatcher(self)
        return True

    def _start_companion_chat_dispatcher(self):
        queue_lock = getattr(self, "_companion_chat_queue_lock", None)
        if queue_lock is None:
            return
        with queue_lock:
            worker = getattr(self, "_companion_chat_dispatch_thread", None)
            if worker is not None and worker.is_alive():
                return
            worker = threading.Thread(
                target=ConnectionHandler._companion_chat_worker,
                args=(self,),
                name="companion-chat-dispatch",
                daemon=True,
            )
            self._companion_chat_dispatch_thread = worker
            worker.start()

    def _companion_chat_worker(self):
        """Drain deferred turns without consuming a connection executor slot."""
        queue_lock = getattr(self, "_companion_chat_queue_lock", None)
        pending_queue = getattr(self, "_companion_chat_queue", None)
        if queue_lock is None or pending_queue is None:
            return
        while True:
            stop_event = getattr(self, "stop_event", None)
            if getattr(self, "_closed", False) or (
                stop_event is not None and stop_event.is_set()
            ):
                with queue_lock:
                    pending_queue.clear()
                    self._companion_chat_pending = False
                break
            with queue_lock:
                if not pending_queue:
                    self._companion_chat_pending = False
                    break
                queued = pending_queue[0]
                if len(queued) == 3:
                    query, depth, enqueued_at = queued
                else:
                    # Keep compatibility with integrations that populated the
                    # queue before queue expiry metadata was introduced.
                    query, depth = queued
                    enqueued_at = time.monotonic()

            if time.monotonic() - enqueued_at >= ConnectionHandler._companion_chat_queue_timeout(self):
                with queue_lock:
                    if pending_queue and pending_queue[0] in {
                        (query, depth, enqueued_at), (query, depth)
                    }:
                        pending_queue.popleft()
                    self._companion_chat_pending = bool(pending_queue)
                ConnectionHandler._abort_chat_for_provider_busy(self, queued=False)
                continue

            state_lock = getattr(self, "companion_mutex", None)
            state_acquired = False
            provider_lock = None
            provider_acquired = False
            queue_expired = False
            retry_after_release = False
            retry_delay = 0.01
            queue_deadline = enqueued_at + ConnectionHandler._companion_chat_queue_timeout(self)
            try:
                if state_lock is not None and hasattr(state_lock, "acquire"):
                    state_lock.acquire()
                    state_acquired = True
                if getattr(self, "_closed", False) or (
                    stop_event is not None and stop_event.is_set()
                ):
                    continue
                preempt_planner = getattr(self, "_companion_planner_preempt", None)
                if callable(preempt_planner):
                    preempt_planner()
                provider_getter = getattr(self, "_get_companion_provider_lock", None)
                if callable(provider_getter):
                    provider_lock = provider_getter()
                else:
                    provider_lock = getattr(self, "_companion_provider_lock", None)
                    if provider_lock is None:
                        provider_lock = getattr(self, "companion_provider_gate", None)
                    if provider_lock is None:
                        provider_lock = threading.Lock()
                    self._companion_provider_lock = provider_lock
                    self.companion_provider_gate = provider_lock
                if not getattr(self, "_closed", False) and not (
                    stop_event is not None and stop_event.is_set()
                ):
                    remaining = queue_deadline - time.monotonic()
                    if remaining <= 0:
                        queue_expired = True
                    else:
                        # Do not hold the state gate while waiting for a
                        # provider owned by a stalled planner. A nonblocking
                        # attempt preserves the state->provider lock order;
                        # the outer loop retries after releasing state.
                        try:
                            provider_acquired = provider_lock.acquire(False)
                        except TypeError:
                            provider_acquired = provider_lock.acquire(timeout=0)
                        if not provider_acquired:
                            retry_after_release = True
                            retry_delay = min(0.01, remaining)
                if provider_acquired and time.monotonic() >= queue_deadline:
                    # Do not start a turn after its queue budget elapsed while
                    # the lock acquisition was waking up.
                    provider_lock.release()
                    provider_acquired = False
                    queue_expired = True
                if not provider_acquired:
                    if queue_expired:
                        with queue_lock:
                            if pending_queue and pending_queue[0] in {
                                (query, depth, enqueued_at), (query, depth)
                            }:
                                pending_queue.popleft()
                            self._companion_chat_pending = bool(pending_queue)
                        ConnectionHandler._abort_chat_for_provider_busy(
                            self, queued=False
                        )
                else:
                    with queue_lock:
                        if pending_queue and pending_queue[0] in {
                            (query, depth, enqueued_at), (query, depth)
                        }:
                            pending_queue.popleft()
                        self._companion_chat_pending = bool(pending_queue)
                    self.chat_in_progress = True
                    self.client_abort = False
                    if query is not None:
                        notify_activity = getattr(self, "notify_confirmed_user_activity", None)
                        if callable(notify_activity):
                            notify_activity()
                    chat_impl = getattr(self, "_chat_impl", None)
                    if not callable(chat_impl):
                        raise RuntimeError("chat implementation unavailable")
                    chat_impl(query, depth)
            except Exception as error:
                logger = getattr(self, "logger", None)
                if logger is not None:
                    try:
                        logger.bind(tag=TAG).warning(
                            f"排队聊天处理失败: {type(error).__name__}"
                        )
                    except Exception:
                        pass
            finally:
                if provider_acquired and provider_lock is not None:
                    provider_lock.release()
                self.chat_in_progress = False
                if state_acquired:
                    state_lock.release()
            if retry_after_release:
                time.sleep(retry_delay)
        with queue_lock:
            if getattr(self, "_companion_chat_dispatch_thread", None) is threading.current_thread():
                self._companion_chat_dispatch_thread = None

    def chat(self, query, depth=0):
        top_level = depth == 0
        mutex = getattr(self, "companion_mutex", None) if top_level else None
        acquired = False
        provider_lock = None
        provider_acquired = False
        if top_level and mutex is not None and hasattr(mutex, "acquire"):
            mutex.acquire()
            acquired = True
        if top_level:
            # Hold the state gate before preempting so no planner reservation
            # can be created between the preemption and the chat turn.
            self.chat_in_progress = True
            try:
                preempt_planner = getattr(self, "_companion_planner_preempt", None)
                if callable(preempt_planner):
                    preempt_planner()
                if query is not None:
                    self.notify_confirmed_user_activity()
                get_provider_lock = getattr(self, "_get_companion_provider_lock", None)
                if callable(get_provider_lock):
                    provider_lock = get_provider_lock()
                else:
                    provider_lock = getattr(self, "_companion_provider_lock", None)
                    if provider_lock is None:
                        provider_lock = getattr(self, "companion_provider_gate", None)
                    if provider_lock is None:
                        provider_lock = threading.Lock()
                    self._companion_provider_lock = provider_lock
                    self.companion_provider_gate = provider_lock
                try:
                    provider_acquired = provider_lock.acquire(
                        timeout=ConnectionHandler._companion_provider_wait_timeout(self)
                    )
                except TypeError:
                    provider_acquired = provider_lock.acquire(False)
                if not provider_acquired:
                    queued = ConnectionHandler._enqueue_companion_chat(self, query, depth)
                    ConnectionHandler._abort_chat_for_provider_busy(self, queued=queued)
                    self.chat_in_progress = False
                    if acquired:
                        mutex.release()
                        acquired = False
                    return None
            except BaseException:
                self.chat_in_progress = False
                if acquired:
                    mutex.release()
                raise
        try:
            return self._chat_impl(query, depth)
        finally:
            if top_level:
                if provider_acquired:
                    provider_lock.release()
                self.chat_in_progress = False
            if acquired:
                mutex.release()

    def _chat_impl(self, query, depth=0):
        # 保存当前任务的sentence_id到局部变量，避免被新任务覆盖
        current_sentence_id = None
        if depth == 0:
            self._refresh_private_config_for_turn()
        companion_enabled = bool(
            self.config.get("companion", {}).get("enabled", False)
        )

        if query is not None:
            self.logger.bind(tag=TAG).info(f"大模型收到用户消息: {query}")

        # 为最顶层时新建会话ID和发送FIRST请求
        if depth == 0:
            current_sentence_id = str(uuid.uuid4().hex)
            self.sentence_id = current_sentence_id  # 更新共享属性
            self._begin_skill_turn(query)
            self.dialogue.put(Message(role="user", content=query))
            started_ready = threading.Event()
            with self._debug_lifecycle_lock:
                self._debug_llm_started_at[current_sentence_id] = (
                    time.monotonic(),
                    started_ready,
                )
                self._debug_llm_finished.pop(current_sentence_id, None)
                self._debug_llm_first_visible.discard(current_sentence_id)
            try:
                self.emit_debug_event(
                    "model_tool",
                    "llm.started",
                    "info",
                    "模型开始回复",
                    details={
                        **module_details(self.config, "LLM"),
                        "selected_module": self.config.get("selected_module", {}).get(
                            "LLM"
                        ),
                        "userText": query or "",
                        "toolMode": (
                            self.intent_type
                            if self.intent_type in {"function_call", "intent_llm"}
                            else "chat"
                        ),
                    },
                    sentence_id=current_sentence_id,
                )
            finally:
                started_ready.set()
            if not companion_enabled:
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.FIRST,
                        content_type=ContentType.ACTION,
                    )
                )
        else:
            # 递归调用时，使用当前的sentence_id
            current_sentence_id = self.sentence_id

        # 设置最大递归深度，避免无限循环，可根据实际需求调整
        MAX_DEPTH = 5
        force_final_answer = False  # 标记是否强制最终回答

        if depth >= MAX_DEPTH:
            self.logger.bind(tag=TAG).debug(
                f"已达到最大工具调用深度 {MAX_DEPTH}，将强制基于现有信息回答"
            )
            force_final_answer = True
            # 添加系统指令，要求 LLM 基于现有信息回答
            self.dialogue.put(
                Message(
                    role="user",
                    content="[系统提示] 已达到最大工具调用次数限制，请你基于目前已经获取的所有信息，直接给出最终答案。不要再尝试调用任何工具。",
                )
            )

        functions, tool_choice = self._prepare_model_functions(
            query, depth, force_final_answer
        )

        response_message = []

        def dispatch_companion_emotion(expression):
            log_context = {
                "tag": TAG,
                "session_id": self.session_id,
                "sentence_id": current_sentence_id,
                "display_emotion": expression.display_emotion,
                "cue": expression.cue,
            }

            def log_dispatch_failure():
                self.logger.bind(
                    event="companion_emotion_dispatch_failed",
                    **log_context,
                ).warning(
                    "event=companion_emotion_dispatch_failed "
                    f"session_id={self.session_id} "
                    f"sentence_id={current_sentence_id} "
                    f"display_emotion={expression.display_emotion} "
                    f"cue={expression.cue}"
                )

            async def send_and_log():
                try:
                    await textUtils.send_companion_emotion(self, expression)
                except Exception:
                    log_dispatch_failure()
                    return
                self.logger.bind(
                    event="companion_emotion_dispatched",
                    **log_context,
                ).info(
                    "event=companion_emotion_dispatched "
                    f"session_id={self.session_id} "
                    f"sentence_id={current_sentence_id} "
                    f"display_emotion={expression.display_emotion} "
                    f"cue={expression.cue}"
                )

            def create_send_task():
                send_task = send_and_log()
                try:
                    self.loop.create_task(send_task)
                except Exception:
                    send_task.close()
                    log_dispatch_failure()

            if self.loop is None or not self.loop.is_running():
                log_dispatch_failure()
                return
            try:
                self.loop.call_soon_threadsafe(create_send_task)
            except Exception:
                log_dispatch_failure()

        def observe_companion_cue(expression, cue_path):
            cue_file = os.path.basename(cue_path)
            self.logger.bind(
                tag=TAG,
                event="companion_cue_enqueued",
                session_id=self.session_id,
                sentence_id=current_sentence_id,
                display_emotion=expression.display_emotion,
                cue=expression.cue,
                cue_file=cue_file,
            ).info(
                "event=companion_cue_enqueued "
                f"session_id={self.session_id} sentence_id={current_sentence_id} "
                f"display_emotion={expression.display_emotion} "
                f"cue={expression.cue} cue_file={cue_file}"
            )

        companion_reply = (
            CompanionStreamingReply(
                current_sentence_id,
                self.tts.tts_text_queue,
                on_expression=dispatch_companion_emotion,
                companion_config=self.config.get("companion", {}),
                on_cue_enqueued=observe_companion_cue,
                user_input=query,
            )
            if companion_enabled and depth == 0
            else None
        )
        companion_parser = (
            CompanionReplyStreamParser()
            if companion_enabled and depth > 0
            else None
        )

        try:
            # 使用带记忆的对话
            memory_str = None
            if not query:
                self.emit_debug_event(
                    "model_tool",
                    "memory.query_skipped",
                    "info",
                    "记忆查询已跳过",
                    details={"reason": "empty_query"},
                    sentence_id=current_sentence_id,
                )
            elif self._memory_debug_skip_reason() is not None:
                self.emit_debug_event(
                    "model_tool",
                    "memory.query_skipped",
                    "info",
                    "记忆查询已跳过",
                    details={"reason": self._memory_debug_skip_reason()},
                    sentence_id=current_sentence_id,
                )
            else:
                memory_started = time.monotonic()
                memory_details = {
                    **module_details(self.config, "Memory"),
                    **memory_query_details(query),
                }
                self.emit_debug_event(
                    "model_tool",
                    "memory.query_started",
                    "info",
                    "记忆查询已开始",
                    details=memory_details,
                    sentence_id=current_sentence_id,
                )
                try:
                    future = asyncio.run_coroutine_threadsafe(
                        self.memory.query_memory(query), self.loop
                    )
                    memory_str = future.result()
                    diagnostics = None
                    get_diagnostics = getattr(self.memory, "get_diagnostics", None)
                    if callable(get_diagnostics):
                        try:
                            diagnostics = get_diagnostics()
                        except Exception:
                            diagnostics = None
                    self.emit_debug_event(
                        "model_tool",
                        "memory.query_completed",
                        "info",
                        "记忆查询已完成",
                        details={
                            **module_details(self.config, "Memory"),
                            **memory_query_details(query, memory_str, diagnostics),
                        },
                        sentence_id=current_sentence_id,
                        duration_ms=max(
                            0, int((time.monotonic() - memory_started) * 1000)
                        ),
                    )
                except Exception as error:
                    self.emit_debug_event(
                        "model_tool",
                        "memory.query_failed",
                        "error",
                        "记忆查询失败",
                        details={"errorClass": type(error).__name__},
                        sentence_id=current_sentence_id,
                        duration_ms=max(
                            0, int((time.monotonic() - memory_started) * 1000)
                        ),
                    )
                    raise

            # 仅在该说话人首次出现时把身份注入 system，之后靠对话历史首轮保留，
            # 避免每轮在 system 重复出现名字诱导模型反复称呼
            speaker_for_system = None
            cs = (self.current_speaker or "").strip()
            if cs and cs != "未知说话人" and cs not in self.system_introduced_speakers:
                self.system_introduced_speakers.add(cs)
                speaker_for_system = cs

            llm_dialogue = self.dialogue.get_llm_dialogue_with_memory(
                memory_str, self.config.get("voiceprint", {}), speaker_for_system
            )
            active_skill = getattr(getattr(self, "_skill_turn", None), "skill", None)
            llm_dialogue = self._skill_runtime.inject_execution_prompt(
                llm_dialogue, active_skill
            )
            if self.intent_type == "function_call" and functions is not None:
                # 使用支持functions的streaming接口
                function_kwargs = {}
                if tool_choice is not None:
                    parameters = inspect.signature(
                        self.llm.response_with_functions
                    ).parameters.values()
                    if any(
                        parameter.name == "tool_choice"
                        or parameter.kind == inspect.Parameter.VAR_KEYWORD
                        for parameter in parameters
                    ):
                        function_kwargs["tool_choice"] = tool_choice
                llm_responses = self.llm.response_with_functions(
                    self.session_id,
                    llm_dialogue,
                    functions=functions,
                    **function_kwargs,
                )
            else:
                llm_responses = self.llm.response(
                    self.session_id,
                    llm_dialogue,
                )
        except Exception as e:
            self._fail_llm_debug(current_sentence_id, e)
            if depth == 0:
                self._finish_skill_turn(e)
            self.logger.bind(tag=TAG).error(f"LLM 处理出错 {query}: {e}")
            if companion_reply is not None:
                companion_reply.feed(get_system_error_response(self.config))
                companion_reply.finish()
            return None

        # 处理流式响应
        tool_call_flag = False
        # 支持多个并行工具调用 - 使用列表存储
        tool_calls_list = []  # 格式: [{"id": "", "name": "", "arguments": ""}]
        content_arguments = ""
        emotion_flag = True
        try:
            for response in llm_responses:
                if self.client_abort:
                    self._fail_llm_debug(
                        current_sentence_id, RuntimeError("cancelled"), "Cancelled"
                    )
                    break
                if self.intent_type == "function_call" and functions is not None:
                    content, tools_call = response
                    if "content" in response:
                        content = response["content"]
                        tools_call = None
                    if content is not None and len(content) > 0:
                        content_arguments += content

                    if not tool_call_flag and content_arguments.startswith("<tool_call>"):
                        # print("content_arguments", content_arguments)
                        tool_call_flag = True

                    if tools_call is not None and len(tools_call) > 0:
                        tool_call_flag = True
                        self._merge_tool_calls(tool_calls_list, tools_call)

                    if tool_call_flag and companion_reply is not None:
                        companion_reply.start()
                        companion_reply = None

                else:
                    content = response

                # 在llm回复中获取情绪表情，一轮对话只在开头获取一次
                if (
                    companion_reply is None
                    and emotion_flag
                    and content is not None
                    and content.strip()
                ):
                    if (self.features or {}).get("emoji", True):
                        asyncio.run_coroutine_threadsafe(
                            textUtils.get_emotion(self, content),
                            self.loop,
                        )
                    emotion_flag = False

                if content is not None and len(content) > 0:
                    if not tool_call_flag:
                        if companion_reply is not None:
                            self._emit_llm_first_visible(
                                current_sentence_id, content
                            )
                            companion_reply.feed(content)
                        elif companion_parser is not None:
                            for visible_content in companion_parser.feed(content):
                                self._emit_llm_first_visible(
                                    current_sentence_id, visible_content
                                )
                                response_message.append(visible_content)
                                self.tts.tts_text_queue.put(
                                    TTSMessageDTO(
                                        sentence_id=current_sentence_id,
                                        sentence_type=SentenceType.MIDDLE,
                                        content_type=ContentType.TEXT,
                                        content_detail=visible_content,
                                    )
                                )
                        else:
                            self._emit_llm_first_visible(
                                current_sentence_id, content
                            )
                            response_message.append(content)
                            self.tts.tts_text_queue.put(
                                TTSMessageDTO(
                                    sentence_id=current_sentence_id,
                                    sentence_type=SentenceType.MIDDLE,
                                    content_type=ContentType.TEXT,
                                    content_detail=content,
                                )
                            )
        except Exception as e:
            self._fail_llm_debug(current_sentence_id, e)
            if depth == 0:
                self._finish_skill_turn(e)
            self.logger.bind(tag=TAG).error(f"LLM stream processing error: {e}")
            if companion_reply is not None:
                companion_reply.feed(get_system_error_response(self.config))
                companion_reply.finish()
            else:
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.MIDDLE,
                        content_type=ContentType.TEXT,
                        content_detail=get_system_error_response(self.config),
                    )
                )
                if depth == 0:
                    self.tts.tts_text_queue.put(
                        TTSMessageDTO(
                            sentence_id=current_sentence_id,
                            sentence_type=SentenceType.LAST,
                            content_type=ContentType.ACTION,
                        )
                    )
            return
        # 处理function call
        if tool_call_flag:
            bHasError = False
            # 处理基于文本的工具调用格式
            if len(tool_calls_list) == 0 and content_arguments:
                a = extract_json_from_string(content_arguments)
                if a is not None:
                    try:
                        content_arguments_json = json.loads(a)
                        tool_calls_list.append(
                            {
                                "id": str(uuid.uuid4().hex),
                                "name": content_arguments_json["name"],
                                "arguments": json.dumps(
                                    content_arguments_json["arguments"],
                                    ensure_ascii=False,
                                ),
                            }
                        )
                    except Exception as e:
                        bHasError = True
                        response_message.append(a)
                else:
                    bHasError = True
                    response_message.append(content_arguments)
                if bHasError:
                    self.logger.bind(tag=TAG).error(
                        f"function call error: {content_arguments}"
                    )

            if not bHasError and len(tool_calls_list) > 0:
                # 处理 direct_answer 虚拟工具
                direct_answer_calls = [tc for tc in tool_calls_list if tc["name"] == "direct_answer"]
                real_tool_calls = [tc for tc in tool_calls_list if tc["name"] != "direct_answer"]

                if direct_answer_calls:
                    self.logger.bind(tag=TAG).debug(
                        f"模型选择 direct_answer，流式已播报，写入对话历史"
                    )
                    for tc in direct_answer_calls:
                        da_response = strip_companion_reply_metadata(
                            self._clean_response_garbage(
                                self._extract_direct_answer_response(
                                    tc.get("arguments", "{}")
                                )
                            )
                        )
                        if da_response:
                            self._emit_llm_first_visible(
                                current_sentence_id, da_response
                            )
                            self.tts.tts_text_queue.put(
                                TTSMessageDTO(
                                    sentence_id=current_sentence_id,
                                    sentence_type=SentenceType.MIDDLE,
                                    content_type=ContentType.TEXT,
                                    content_detail=da_response,
                                )
                            )
                            self.tts.store_tts_text(current_sentence_id, da_response)
                            self.dialogue.put(Message(role="assistant", content=da_response))

                    if not real_tool_calls:
                        for tc in direct_answer_calls:
                            da_response = strip_companion_reply_metadata(
                                self._clean_response_garbage(
                                    self._extract_direct_answer_response(
                                        tc.get("arguments", "{}")
                                    )
                                )
                            )
                            self._finish_llm_debug(
                                current_sentence_id, da_response
                            )
                        if depth == 0:
                            self._skill_result_class = "DIRECT_ANSWER"
                            self._finish_skill_turn()
                            self.tts.tts_text_queue.put(
                                TTSMessageDTO(
                                    sentence_id=current_sentence_id,
                                    sentence_type=SentenceType.LAST,
                                    content_type=ContentType.ACTION,
                                )
                            )
                        return

                    tool_calls_list = real_tool_calls

            if not bHasError and len(tool_calls_list) > 0:
                self.logger.bind(tag=TAG).debug(
                    f"检测到 {len(tool_calls_list)} 个工具调用"
                )

                # LLM 流式阶段已播报过的文本
                streamed_text = ""
                if len(response_message) > 0:
                    streamed_text = "".join(response_message)
                    self.tts.store_tts_text(current_sentence_id, streamed_text)
                    self.dialogue.put(Message(role="assistant", content=streamed_text))
                response_message.clear()

                # 收集所有工具调用的 Future
                futures_with_data = []
                for tool_call_data in tool_calls_list:
                    self.logger.bind(tag=TAG).debug(
                        f"function_name={tool_call_data['name']}, function_id={tool_call_data['id']}, function_arguments={tool_call_data['arguments']}"
                    )

                    # 使用公共方法上报工具调用
                    tool_input = json.loads(tool_call_data.get("arguments") or "{}")
                    enqueue_tool_report(self, tool_call_data['name'], tool_input)

                    future = asyncio.run_coroutine_threadsafe(
                        self.func_handler.handle_llm_function_call(
                            self, tool_call_data
                        ),
                        self.loop,
                    )
                    futures_with_data.append((future, tool_call_data, tool_input))

                # 工具调用超时时间，可配置，默认30秒
                tool_call_timeout = self._skill_tool_timeout_seconds()
                # 等待协程结束（实际等待时长为最慢的那个）
                tool_results = []

                for future, tool_call_data, tool_input in futures_with_data:
                    try:
                        result = future.result(timeout=tool_call_timeout)
                        tool_results.append((result, tool_call_data))
                        # 使用公共方法上报工具调用结果
                        enqueue_tool_report(self, tool_call_data['name'], tool_input, str(result.result) if result.result else None, report_tool_call=False)

                    except Exception as e:
                        self.logger.bind(tag=TAG).error(
                            f"工具调用超时或异常: {tool_call_data['name']}, 错误: {e}"
                        )
                        # 超时时返回错误响应，避免整个流程卡死
                        tool_results.append((
                            ActionResponse(action=Action.ERROR, result="哎呀，网络遇到点问题，请稍后再试下！"),
                            tool_call_data
                        ))
                        # 上报工具调用错误
                        enqueue_tool_report(self, tool_call_data['name'], tool_input, str(e), report_tool_call=False)

                # 统一处理工具调用结果
                if tool_results:
                    self._handle_function_result(tool_results, depth=depth, streamed_text=streamed_text)

        if companion_parser is not None:
            for visible_content in companion_parser.finish():
                self._emit_llm_first_visible(
                    current_sentence_id, visible_content
                )
                response_message.append(visible_content)
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.MIDDLE,
                        content_type=ContentType.TEXT,
                        content_detail=visible_content,
                    )
                )

        if companion_reply is not None:
            companion_reply.finish()
            response_message = list(companion_reply.text_parts)

        # 存储对话内容
        if len(response_message) > 0:
            text_buff = "".join(response_message)
            self.tts.store_tts_text(current_sentence_id, text_buff)
            self.dialogue.put(Message(role="assistant", content=text_buff))
            self._finish_llm_debug(current_sentence_id, text_buff)
        elif not tool_call_flag:
            self._finish_llm_debug(current_sentence_id, "")

        if depth == 0:
            self._finish_skill_turn()
            if companion_reply is None:
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.LAST,
                        content_type=ContentType.ACTION,
                    )
                )
            # 使用lambda延迟计算，只有在DEBUG级别时才执行get_llm_dialogue()
            self.logger.bind(tag=TAG).debug(
                lambda: json.dumps(
                    self.dialogue.get_llm_dialogue(), indent=4, ensure_ascii=False
                )
            )

        return True

    def _handle_function_result(self, tool_results, depth, streamed_text=""):
        need_llm_tools = []
        record_tools = []
        visible_tool_replies = []

        for result, tool_call_data in tool_results:
            result = self._apply_skill_response_policy(result)
            action_name = getattr(getattr(result, "action", None), "name", "UNKNOWN")
            if action_name in {"ERROR", "NOTFOUND"}:
                self._skill_result_class = action_name
                active_skill = getattr(
                    getattr(self, "_skill_turn", None), "skill", None
                )
                if active_skill is not None and active_skill.failure_message:
                    result.response = active_skill.failure_message
            elif self._skill_result_class not in {"ERROR", "NOTFOUND"}:
                self._skill_result_class = action_name
            if result.action in [
                Action.RESPONSE,
                Action.NOTFOUND,
                Action.ERROR,
            ]:
                text = strip_companion_reply_metadata(
                    result.response if result.response else result.result
                )
                if streamed_text and text in streamed_text:
                    self.logger.bind(tag=TAG).debug(
                        f"Skipping duplicate TTS for tool {tool_call_data['name']}, already streamed"
                    )
                else:
                    self.tts.tts_one_sentence(self, ContentType.TEXT, content_detail=text)
                    self.tts.store_tts_text(self.sentence_id, text)
                self.dialogue.put(Message(role="assistant", content=text))
                if text:
                    visible_tool_replies.append(text)
            elif result.action == Action.REQLLM:
                need_llm_tools.append((result, tool_call_data))
            elif result.action == Action.RECORD:
                record_tools.append((result, tool_call_data))
            else:
                pass

        # Action.RECORD：写入完整工具调用链（assistant(tool_calls) → tool(result) → assistant(response)）
        # 模型从历史中学到工具调用模式，不额外调用LLM
        if record_tools:
            # 构造 assistant 消息（含 tool_calls），记录"模型调用了哪些工具"
            all_tool_calls = [
                {
                    "id": tool_call_data["id"],
                    "function": {
                        "arguments": (
                            "{}"
                            if tool_call_data["arguments"] == ""
                            else tool_call_data["arguments"]
                        ),
                        "name": tool_call_data["name"],
                    },
                    "type": "function",
                    "index": idx,
                }
                for idx, (_, tool_call_data) in enumerate(record_tools)
            ]
            self.dialogue.put(Message(role="assistant", tool_calls=all_tool_calls))

            # 写入每条工具的执行结果，记录"工具返回了什么"
            for result, tool_call_data in record_tools:
                text = result.result or ""
                self.dialogue.put(
                    Message(
                        role="tool",
                        tool_call_id=(
                            str(uuid.uuid4())
                            if tool_call_data["id"] is None
                            else tool_call_data["id"]
                        ),
                        content=text,
                    )
                )

            # 用固定文本作为最终回复，补全标准三段式，保证下一条消息是 user 而非接 tool
            response_parts = []
            for result, _ in record_tools:
                resp = result.response or result.result
                if resp:
                    response_parts.append(resp)
            if response_parts:
                record_reply = "，".join(response_parts)
                self.dialogue.put(Message(role="assistant", content=record_reply))
                visible_tool_replies.append(record_reply)

        if need_llm_tools:
            all_tool_calls = [
                {
                    "id": tool_call_data["id"],
                    "function": {
                        "arguments": (
                            "{}"
                            if tool_call_data["arguments"] == ""
                            else tool_call_data["arguments"]
                        ),
                        "name": tool_call_data["name"],
                    },
                    "type": "function",
                    "index": idx,
                }
                for idx, (_, tool_call_data) in enumerate(need_llm_tools)
            ]
            self.dialogue.put(Message(role="assistant", tool_calls=all_tool_calls))

            for result, tool_call_data in need_llm_tools:
                text = result.result
                if text is not None and len(text) > 0:
                    self.dialogue.put(
                        Message(
                            role="tool",
                            tool_call_id=(
                                str(uuid.uuid4())
                                if tool_call_data["id"] is None
                                else tool_call_data["id"]
                            ),
                            content=text,
                        )
                    )

            self.chat(None, depth=depth + 1)
        elif visible_tool_replies:
            self._finish_llm_debug(self.sentence_id, "".join(visible_tool_replies))

    def _report_worker(self):
        """聊天记录上报工作线程"""
        while not self.stop_event.is_set():
            try:
                # 从队列获取数据，设置超时以便定期检查停止事件
                item = self.report_queue.get(timeout=1)
                if item is None:  # 检测毒丸对象
                    break
                try:
                    # 检查线程池状态
                    if self.executor is None:
                        continue
                    # 提交任务到线程池
                    self.executor.submit(self._process_report, *item)
                except Exception as e:
                    self.logger.bind(tag=TAG).error(f"聊天记录上报线程异常: {e}")
            except queue.Empty:
                continue
            except Exception as e:
                self.logger.bind(tag=TAG).error(f"聊天记录上报工作线程异常: {e}")

        self.logger.bind(tag=TAG).info("聊天记录上报线程已退出")

    def _process_report(self, type, text, audio_data, report_time):
        """处理上报任务"""
        try:
            # 执行异步上报（在事件循环中运行）
            asyncio.run(report(self, type, text, audio_data, report_time))
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"上报处理异常: {e}")
        finally:
            # 标记任务完成
            self.report_queue.task_done()

    def clearSpeakStatus(self):
        self.client_is_speaking = False
        self.logger.bind(tag=TAG).debug(f"清除服务端讲话状态")

    async def close(self, ws=None):
        """资源清理方法"""
        self._ensure_proactive_state()
        with self._proactive_state_lock:
            self._closed = True
            self._pending_proactive_messages.clear()
            for completion in self._proactive_completion_futures.values():
                if not completion.done():
                    completion.set_result(False)
            self._proactive_completion_futures.clear()
        chat_queue_lock = getattr(self, "_companion_chat_queue_lock", None)
        chat_queue = getattr(self, "_companion_chat_queue", None)
        if chat_queue_lock is not None and chat_queue is not None:
            with chat_queue_lock:
                chat_queue.clear()
                self._companion_chat_pending = False
        chat_worker = getattr(self, "_companion_chat_dispatch_thread", None)
        if (
            chat_worker is not None
            and chat_worker is not threading.current_thread()
            and chat_worker.is_alive()
        ):
            await asyncio.to_thread(chat_worker.join, 0.25)
        background_task = getattr(self, "_background_initialize_task", None)
        current_task = asyncio.current_task()
        if background_task is not None and background_task is not current_task and not background_task.done():
            background_task.cancel()
            try:
                await background_task
            except asyncio.CancelledError:
                pass
        self._background_initialize_task = None
        component_future = getattr(self, "_component_init_future", None)
        if component_future is not None and not component_future.done():
            component_future.cancel()
        self._component_init_future = None
        channel_future = getattr(self, "_component_channel_future", None)
        if channel_future is not None and not channel_future.done():
            channel_future.cancel()
        self._component_channel_future = None
        if getattr(self, "proactive_playback_active", False):
            self.cancel_proactive_playback()
        current_task = asyncio.current_task()
        memory_tasks = [
            task
            for task in self._proactive_memory_tasks
            if task is not current_task and not task.done()
        ]
        for task in memory_tasks:
            task.cancel()
        if memory_tasks:
            await asyncio.gather(*memory_tasks, return_exceptions=True)
        self._proactive_memory_tasks.clear()
        companion_start_task = getattr(self, "_companion_start_task", None)
        if companion_start_task is not None and not companion_start_task.done():
            companion_start_task.cancel()
            try:
                await companion_start_task
            except asyncio.CancelledError:
                pass
        self._companion_start_task = None
        if self._companion_loop is not None:
            try:
                await self._companion_loop.stop()
            except Exception as error:
                self.logger.bind(tag=TAG).debug(f"主动陪伴循环关闭失败: {type(error).__name__}")
            self._companion_loop = None
        with self._debug_lifecycle_lock:
            active_llm_sentence_ids = list(self._debug_llm_started_at)
        for sentence_id in active_llm_sentence_ids:
            self._fail_llm_debug(
                sentence_id, RuntimeError("connection closed"), "Cancelled"
            )
        reporter = self.debug_events
        if reporter is not None and not self._debug_connection_closed:
            self._debug_connection_closed = True
            self.emit_debug_event(
                "device", "connection.closed", "info", "设备连接已关闭"
            )
        try:
            # 清理 VAD 连接资源
            if (
                    hasattr(self, "vad")
                    and self.vad
                    and hasattr(self.vad, "release_conn_resources")
            ):
                self.vad.release_conn_resources(self)

            # 清理opus解码器
            if hasattr(self, "_connection_opus_decoder"):
                try:
                    delattr(self, "_connection_opus_decoder")
                except Exception:
                    pass

            # 清理音频缓冲区
            if hasattr(self, "audio_buffer"):
                self.audio_buffer.clear()

            # 取消超时任务
            current_task = asyncio.current_task()
            if self.timeout_task and self.timeout_task is not current_task and not self.timeout_task.done():
                self.timeout_task.cancel()
                try:
                    await self.timeout_task
                except asyncio.CancelledError:
                    pass
                self.timeout_task = None
            elif self.timeout_task is current_task:
                self.timeout_task = None

            # 取消AEC缓存清理任务
            if hasattr(self, "_aec_cache_cleanup_task") and self._aec_cache_cleanup_task and not self._aec_cache_cleanup_task.done():
                self._aec_cache_cleanup_task.cancel()
                try:
                    await self._aec_cache_cleanup_task
                except asyncio.CancelledError:
                    pass
                self._aec_cache_cleanup_task = None

            # 清理AEC缓存
            if hasattr(self, "aec_audio_cache"):
                self.aec_audio_cache.clear()
                self.aec_audio_cache_time.clear()

            # 清理工具处理器资源
            if hasattr(self, "func_handler") and self.func_handler:
                try:
                    await self.func_handler.cleanup()
                except Exception as cleanup_error:
                    self.logger.bind(tag=TAG).error(
                        f"清理工具处理器时出错: {cleanup_error}"
                    )

            # 触发停止事件
            if self.stop_event:
                self.stop_event.set()

            # 清空任务队列
            self.clear_queues()

            # 关闭WebSocket连接
            try:
                if ws:
                    # 安全地检查WebSocket状态并关闭
                    try:
                        if hasattr(ws, "closed") and not ws.closed:
                            await ws.close()
                        elif hasattr(ws, "state") and ws.state.name != "CLOSED":
                            await ws.close()
                        else:
                            # 如果没有closed属性，直接尝试关闭
                            await ws.close()
                    except Exception:
                        # 如果关闭失败，忽略错误
                        pass
                elif self.websocket:
                    try:
                        if (
                                hasattr(self.websocket, "closed")
                                and not self.websocket.closed
                        ):
                            await self.websocket.close()
                        elif (
                                hasattr(self.websocket, "state")
                                and self.websocket.state.name != "CLOSED"
                        ):
                            await self.websocket.close()
                        else:
                            # 如果没有closed属性，直接尝试关闭
                            await self.websocket.close()
                    except Exception:
                        # 如果关闭失败，忽略错误
                        pass
            except Exception as ws_error:
                self.logger.bind(tag=TAG).error(f"关闭WebSocket连接时出错: {ws_error}")

            if self.tts:
                await self.tts.close()
            if self.asr:
                await self.asr.close()

            # 最后关闭线程池（避免阻塞）
            if self.executor:
                try:
                    self.executor.shutdown(wait=False)
                except Exception as executor_error:
                    self.logger.bind(tag=TAG).error(
                        f"关闭线程池时出错: {executor_error}"
                    )
                self.executor = None
            self.logger.bind(tag=TAG).info("连接资源已释放")
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"关闭连接时出错: {e}")
        finally:
            # 确保停止事件被设置
            if self.stop_event:
                self.stop_event.set()
            if reporter is not None:
                try:
                    reporter.close()
                except Exception as error:
                    self.logger.bind(tag=TAG).debug(
                        f"调试事件关闭失败: {type(error).__name__}"
                    )

    def clear_queues(self):
        """清空所有任务队列"""
        if self.tts:
            self.logger.bind(tag=TAG).debug(
                f"开始清理: TTS队列大小={self.tts.tts_text_queue.qsize()}, 音频队列大小={self.tts.tts_audio_queue.qsize()}"
            )

            # 使用非阻塞方式清空队列
            for q in [
                self.tts.tts_text_queue,
                self.tts.tts_audio_queue,
                self.report_queue,
            ]:
                if not q:
                    continue
                while True:
                    try:
                        q.get_nowait()
                    except queue.Empty:
                        break

            # 重置音频流控器（取消后台任务并清空队列）
            if hasattr(self, "audio_rate_controller") and self.audio_rate_controller:
                self.audio_rate_controller.reset()
                self.logger.bind(tag=TAG).debug("已重置音频流控器")

            self.logger.bind(tag=TAG).debug(
                f"清理结束: TTS队列大小={self.tts.tts_text_queue.qsize()}, 音频队列大小={self.tts.tts_audio_queue.qsize()}"
            )

    def reset_audio_states(self):
        """
        重置所有音频相关状态(VAD + ASR)
        """
        # Reset VAD states
        self.client_audio_buffer.clear()
        self.client_have_voice = False
        self.client_voice_stop = False
        self.client_voice_window.clear()
        self.last_is_voice = False
        self.vad_last_voice_time = 0.0

        # Clear ASR buffers
        self.asr_audio.clear()

        self.logger.bind(tag=TAG).debug("All audio states reset.")

    def chat_and_close(self, text):
        """Chat with the user and then close the connection"""
        try:
            # Use the existing chat method
            self.chat(text)

            # After chat is complete, close the connection
            self.close_after_chat = True
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"Chat and close error: {str(e)}")

    async def _check_timeout(self):
        """检查连接超时"""
        try:
            while not self.stop_event.is_set():
                companion = self.config.get("companion", {})
                if companion.get("enabled") and companion.get("mode") == "proactive":
                    max_seconds = companion.get("max_connection_seconds", 900)
                    try:
                        max_seconds = max(60, int(max_seconds))
                    except (TypeError, ValueError):
                        max_seconds = 900
                    if self.first_activity_time > 0.0:
                        elapsed = time.time() - self.first_activity_time / 1000.0
                        if elapsed >= max_seconds:
                            self.logger.bind(tag=TAG).info(
                                "主动陪伴连接达到资源时限，准备关闭"
                            )
                            self.stop_event.set()
                            try:
                                await self.close(self.websocket)
                            except Exception as close_error:
                                self.logger.bind(tag=TAG).error(
                                    f"主动陪伴资源时限关闭连接时出错: {close_error}"
                                )
                            break
                    await asyncio.sleep(10)
                    continue
                last_activity_time = self.last_activity_time
                if self.need_bind:
                    last_activity_time = self.first_activity_time

                # 检查是否超时（只有在时间戳已初始化的情况下）
                if last_activity_time > 0.0:
                    current_time = time.time() * 1000
                    if current_time - last_activity_time > self.timeout_seconds * 1000:
                        if not self.stop_event.is_set():
                            self.logger.bind(tag=TAG).info("连接超时，准备关闭")
                            # 设置停止事件，防止重复处理
                            self.stop_event.set()
                            # 使用 try-except 包装关闭操作，确保不会因为异常而阻塞
                            try:
                                await self.close(self.websocket)
                            except Exception as close_error:
                                self.logger.bind(tag=TAG).error(
                                    f"超时关闭连接时出错: {close_error}"
                                )
                        break
                # 每10秒检查一次，避免过于频繁
                await asyncio.sleep(10)
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"超时检查任务出错: {e}")
        finally:
            self.logger.bind(tag=TAG).info("超时检查任务已退出")

    async def _check_aec_cache_expiry(self):
        """定期清理过期的AEC缓存"""
        try:
            while not self.stop_event.is_set():
                if hasattr(self, "aec_audio_cache") and self.aec_audio_cache:
                    current_time = time.time()
                    expired_keys = [
                        ts for ts, cache_time in list(self.aec_audio_cache_time.items())
                        if current_time - cache_time > 120  # 2分钟过期
                    ]
                    for ts in expired_keys:
                        self.aec_audio_cache.pop(ts, None)
                        self.aec_audio_cache_time.pop(ts, None)
                    if expired_keys:
                        self.logger.bind(tag=TAG).debug(f"[AEC] 清理过期缓存 {len(expired_keys)} 条")
                # 每30秒检查一次
                await asyncio.sleep(30)
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"AEC缓存清理任务出错: {e}")

    @staticmethod
    def _extract_direct_answer_response(arguments_str):
        """从 direct_answer 的参数中提取 response 值。
        优先使用 json.loads 标准解析，流式阶段 fallback 到字符串提取。
        """
        if not arguments_str:
            return ""
        # 优先尝试标准 JSON 解析（适用于完整且格式正确的 JSON）
        try:
            data = json.loads(arguments_str)
            if isinstance(data, dict) and "response" in data:
                return data["response"]
        except (json.JSONDecodeError, TypeError):
            pass
        # Fallback：流式阶段 JSON 可能不完整，使用字符串提取
        marker = '"response": "'
        idx = arguments_str.find(marker)
        if idx < 0:
            marker = '"response":"'
            idx = arguments_str.find(marker)
        if idx < 0:
            return ""
        start = idx + len(marker)
        raw = arguments_str[start:]
        # 去掉末尾的 JSON 闭合符号（如果已完整）
        if raw.endswith('"}'):
            raw = raw[:-2]
        elif raw.endswith('"'):
            raw = raw[:-1]
        # 处理 JSON 转义
        raw = raw.replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')
        return raw

    @staticmethod
    def _clean_response_garbage(text):
        """清理 response 中可能泄漏的 JSON 闭合符号。
        模型有时会在 response 内容中生成 JSON 闭合字符（如 ）"}} 或 '})，
        这些不是故事内容的一部分，需要去除。
        """
        if not text:
            return text
        # 清理独立一行的 JSON 闭合垃圾（如 ）"}}  '}}  "}}  }}  } ）
        _garbage_chars = frozenset('")\'}）')
        lines = text.split('\n')
        cleaned = []
        for line in lines:
            stripped = line.strip()
            if stripped and len(stripped) <= 8 and all(c in _garbage_chars for c in stripped):
                continue
            cleaned.append(line)
        result = '\n'.join(cleaned)
        # 清理末尾残留的 JSON 闭合符号
        result = re.sub(r'["\'}\]]+$', '', result.rstrip()).rstrip()
        return result

    def _merge_tool_calls(self, tool_calls_list, tools_call):
        """合并工具调用列表

        Args:
            tool_calls_list: 已收集的工具调用列表
            tools_call: 新的工具调用
        """
        for tool_call in tools_call:
            tool_index = getattr(tool_call, "index", None)
            if tool_index is None:
                if tool_call.function.name:
                    # 有 function_name，说明是新的工具调用
                    tool_index = len(tool_calls_list)
                else:
                    tool_index = len(tool_calls_list) - 1 if tool_calls_list else 0

            # 确保列表有足够的位置
            if tool_index >= len(tool_calls_list):
                tool_calls_list.append({"id": "", "name": "", "arguments": ""})

            # 更新工具调用信息
            if tool_call.id:
                tool_calls_list[tool_index]["id"] = tool_call.id
            if tool_call.function.name:
                tool_calls_list[tool_index]["name"] = tool_call.function.name
            if tool_call.function.arguments:
                tool_calls_list[tool_index]["arguments"] += tool_call.function.arguments
