import json
import gzip
import uuid
import asyncio
import time
import websockets
from core.providers.asr.base import ASRProviderBase
from config.logger import setup_logging
from core.providers.asr.dto.dto import InterfaceType
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()


class ASRProvider(ASRProviderBase):
    def __init__(self, config, delete_audio_file):
        super().__init__()
        self.interface_type = InterfaceType.STREAM
        self.config = config
        self.text = ""
        self.asr_ws = None
        self.forward_task = None
        self.is_processing = False  # 添加处理状态标志
        self._is_stopping = False  # 添加停止标志，防止竞态条件
        self._last_definite_text = ""
        self._last_definite_at = 0.0
        self._auto_stop_task = None
        self._auto_stop_handled = False

        # 配置参数
        self.appid = str(config.get("appid"))
        self.access_token = config.get("access_token")
        # 资源ID，用于区分不同的ASR模型（默认1.0模型小时版，v2版本使用seed-asr）
        self.resource_id = config.get("resource_id", "volc.bigasr.sauc.duration")

        self.boosting_table_name = config.get("boosting_table_name", "")
        self.correct_table_name = config.get("correct_table_name", "")
        self.output_dir = config.get("output_dir", "tmp/")
        self.delete_audio_file = delete_audio_file

        # 火山引擎ASR配置
        enable_multilingual = config.get("enable_multilingual", False)
        self.enable_multilingual = (
            False if str(enable_multilingual).lower() == "false" else True
        )
        if self.enable_multilingual:
            self.ws_url = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
        else:
            self.ws_url = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        self.uid = config.get("uid", "streaming_asr_service")
        self.workflow = config.get(
            "workflow", "audio_in,resample,partition,vad,fe,decode,itn,nlu_punctuate"
        )
        self.result_type = config.get("result_type", "single")
        self.format = config.get("format", "pcm")
        self.codec = config.get("codec", "pcm")
        self.rate = config.get("sample_rate", 16000)
        # language参数仅在多语种模式(bigmodel_nostream)下有效
        self.language = config.get("language") if self.enable_multilingual else None
        self.bits = config.get("bits", 16)
        self.channel = config.get("channel", 1)
        self.auth_method = config.get("auth_method", "token")
        self.secret = config.get("secret", "access_secret")
        end_window_size = config.get("end_window_size")
        self.end_window_size = int(end_window_size) if end_window_size else 200
        final_text_stability_ms = config.get("final_text_stability_ms")
        self.final_text_stability_ms = (
            int(final_text_stability_ms) if final_text_stability_ms is not None else 200
        )

    async def open_audio_channels(self, conn):
        await super().open_audio_channels(conn)

    async def receive_audio(self, conn: "ConnectionHandler", pcm_frame, audio_have_voice):
        # 先调用父类方法处理基础逻辑
        await super().receive_audio(conn, pcm_frame, audio_have_voice)

        # 如果本次有声音，且之前没有建立连接
        if audio_have_voice and self.asr_ws is None and not self.is_processing:
            try:
                self.is_processing = True
                self._last_definite_text = ""
                self._last_definite_at = 0.0
                self._auto_stop_handled = False
                # 建立新的WebSocket连接
                headers = self.token_auth() if self.auth_method == "token" else None
                logger.bind(tag=TAG).info("正在连接ASR服务")

                self.asr_ws = await websockets.connect(
                    self.ws_url,
                    additional_headers=headers,
                    max_size=1000000000,
                    ping_interval=None,
                    ping_timeout=None,
                    close_timeout=10,
                )

                # 发送初始化请求
                request_params = self.construct_request(str(uuid.uuid4()))
                try:
                    payload_bytes = str.encode(json.dumps(request_params))
                    payload_bytes = gzip.compress(payload_bytes)
                    full_client_request = self.generate_header()
                    full_client_request.extend((len(payload_bytes)).to_bytes(4, "big"))
                    full_client_request.extend(payload_bytes)

                    logger.bind(tag=TAG).info("发送ASR初始化请求")
                    await self.asr_ws.send(full_client_request)

                    # 等待初始化响应
                    init_res = await self.asr_ws.recv()
                    result = self.parse_response(init_res)
                    logger.bind(tag=TAG).info(f"收到初始化响应: {result}")

                    # 检查初始化响应
                    if "code" in result and result["code"] != 1000:
                        error_msg = f"ASR服务初始化失败: {result.get('payload_msg', {}).get('error', '未知错误')}"
                        logger.bind(tag=TAG).error(error_msg)
                        raise Exception(error_msg)

                except Exception as e:
                    logger.bind(tag=TAG).error(f"发送初始化请求失败: {str(e)}")
                    if hasattr(e, "__cause__") and e.__cause__:
                        logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
                    raise e

                # 启动接收ASR结果的异步任务
                self.forward_task = asyncio.create_task(self._forward_asr_results(conn))

                # 发送缓存的音频数据
                if conn.asr_audio and len(conn.asr_audio) > 0:
                    for cached_pcm in conn.asr_audio[-10:]:
                        try:
                            payload = gzip.compress(cached_pcm)
                            audio_request = bytearray(
                                self.generate_audio_default_header()
                            )
                            audio_request.extend(len(payload).to_bytes(4, "big"))
                            audio_request.extend(payload)
                            await self.asr_ws.send(audio_request)
                        except Exception as e:
                            logger.bind(tag=TAG).info(
                                f"发送缓存音频数据时发生错误: {e}"
                            )

            except Exception as e:
                logger.bind(tag=TAG).error(f"建立ASR连接失败: {str(e)}")
                if hasattr(e, "__cause__") and e.__cause__:
                    logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
                if self.asr_ws:
                    await self.asr_ws.close()
                    self.asr_ws = None
                self.is_processing = False
                return

        # 发送当前音频数据
        if self.asr_ws and self.is_processing and not self._is_stopping:
            try:
                payload = gzip.compress(pcm_frame)
                audio_request = bytearray(self.generate_audio_default_header())
                audio_request.extend(len(payload).to_bytes(4, "big"))
                audio_request.extend(payload)
                await self.asr_ws.send(audio_request)
            except Exception as e:
                logger.bind(tag=TAG).info(f"发送音频数据时发生错误: {e}")

    async def _forward_asr_results(self, conn: "ConnectionHandler"):
        try:
            while self.asr_ws and not conn.stop_event.is_set():
                # 获取当前连接的音频数据
                audio_data = conn.asr_audio
                try:
                    response = await self.asr_ws.recv()
                    result = self.parse_response(response)
                    logger.bind(tag=TAG).debug(f"收到ASR结果: {result}")

                    if "payload_msg" in result:
                        payload = result["payload_msg"]
                        # 检查是否是错误码1013（无有效语音）
                        if "code" in payload and payload["code"] == 1013:
                            # 静默处理，不记录错误日志
                            continue

                        if "result" in payload:
                            result_data = payload["result"]
                            utterances = result_data.get("utterances", [])
                            aggregate_text = (result_data.get("text") or "").strip()
                            # 检查duration和空文本的情况
                            if (
                                not self.enable_multilingual  # 注意：多语种模式不返回中间结果，需要等待最终结果
                                and payload.get("audio_info", {}).get("duration", 0)
                                > 2000
                                and not utterances
                                and not payload["result"].get("text")
                                and conn.client_listen_mode != "manual"
                            ):
                                logger.bind(tag=TAG).error(f"识别文本：空")
                                # 空结果不能绕过自动模式的 VAD/稳定文本边界，
                                # 否则 ASR 的中间空片段会提前结束本轮对话。
                                if conn.client_voice_stop:
                                    if self._auto_result_is_ready(conn):
                                        await self._handle_auto_voice_stop(conn)
                                    break
                                if not self._last_definite_text:
                                    self.text = ""
                                continue

                            # 专门处理没有文本的识别结果（手动模式下可能已经识别完成但是没松按键）
                            elif not payload["result"].get("text") and not utterances:
                                # 多语种模式会持续返回空文本，直到最后返回完整结果，所以需要排除
                                if self.enable_multilingual:
                                    continue

                                if conn.client_listen_mode == "manual" and conn.client_voice_stop and len(audio_data) > 15:
                                    logger.bind(tag=TAG).debug("消息结束收到停止信号，触发处理")
                                    await self.handle_voice_stop(conn, audio_data)
                                    break

                            handled_definite_text = False
                            for utterance in utterances:
                                if utterance.get("definite", False):
                                    current_text = utterance["text"]
                                    logger.bind(tag=TAG).info(
                                        f"识别到文本: {current_text}"
                                    )
                                    handled_definite_text = True

                                    # 手动模式下累积识别结果
                                    if conn.client_listen_mode == "manual":
                                        if self.text:
                                            self.text += current_text
                                        else:
                                            self.text = current_text

                                        # 在接收消息中途时收到停止信号
                                        if conn.client_voice_stop and len(audio_data) > 0:
                                            logger.bind(tag=TAG).debug("消息中途收到停止信号，触发处理")
                                            await self.handle_voice_stop(conn, audio_data)
                                        break
                                    else:
                                        await self._update_auto_final_text(
                                            conn, current_text
                                        )
                                    break
                            if (
                                conn.client_listen_mode != "manual"
                                and not handled_definite_text
                                and aggregate_text
                            ):
                                await self._update_auto_final_text(
                                    conn, aggregate_text
                                )
                        elif "error" in payload:
                            error_msg = payload.get("error", "未知错误")
                            logger.bind(tag=TAG).error(f"ASR服务返回错误: {error_msg}")
                            break

                except websockets.ConnectionClosed:
                    logger.bind(tag=TAG).info("ASR服务连接已关闭")
                    self.is_processing = False
                    break
                except Exception as e:
                    logger.bind(tag=TAG).error(f"处理ASR结果时发生错误: {str(e)}")
                    if hasattr(e, "__cause__") and e.__cause__:
                        logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
                    self.is_processing = False
                    break

        except Exception as e:
            logger.bind(tag=TAG).error(f"ASR结果转发任务发生错误: {str(e)}")
            if hasattr(e, "__cause__") and e.__cause__:
                logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
        finally:
            self._cancel_auto_stop_task()
            if self.asr_ws:
                await self.asr_ws.close()
                self.asr_ws = None
            self.is_processing = False
            self._is_stopping = False
            # 重置所有音频相关状态
            conn.reset_audio_states()

    async def _update_auto_final_text(self, conn, current_text):
        """保存最新最终文本，等待文本稳定且 VAD 确认说完后再提交。"""
        current_text = (current_text or "").strip()
        if not current_text or self._auto_stop_handled:
            return

        if current_text != self._last_definite_text:
            self._last_definite_text = current_text
            self._last_definite_at = time.monotonic()
            self.text = self._accumulate_turn_text(self.text, current_text)

        if self._auto_result_is_ready(conn):
            await self._handle_auto_voice_stop(conn)
            return

        if self._auto_stop_task is None or self._auto_stop_task.done():
            self._auto_stop_task = asyncio.create_task(
                self._wait_for_auto_voice_stop(conn)
            )

    def _auto_result_is_ready(self, conn):
        if not conn.client_voice_stop or not self._last_definite_text:
            return False
        stable_seconds = max(0, self.final_text_stability_ms) / 1000
        return time.monotonic() - self._last_definite_at >= stable_seconds

    @staticmethod
    def _norm_turn_text(text):
        import re

        return re.sub(r"[，。？！,、；;:.!?…\s]+", "", text or "")

    @classmethod
    def _accumulate_turn_text(cls, accumulated, current):
        """合并一句话内的多个确定分句。

        逐句到达的 definite utterance 需要追加（否则只保留最后一句，
        打断场景下用户开头的话会丢失）；聚合的累积文本已包含前文，
        整体替换即可。按去标点后的包含/前缀关系区分两种情况。
        """
        accumulated = (accumulated or "").strip()
        current = (current or "").strip()
        if not accumulated:
            return current
        norm_acc = cls._norm_turn_text(accumulated)
        norm_cur = cls._norm_turn_text(current)
        if not norm_cur or norm_cur in norm_acc:
            return accumulated
        if norm_cur.startswith(norm_acc):
            return current
        return accumulated + current

    async def _wait_for_auto_voice_stop(self, conn):
        try:
            while not self._auto_stop_handled and not conn.stop_event.is_set():
                if self._auto_result_is_ready(conn):
                    await self._handle_auto_voice_stop(conn)
                    return
                await asyncio.sleep(0.02)
        except asyncio.CancelledError:
            pass

    async def _handle_auto_voice_stop(self, conn):
        if self._auto_stop_handled or not self._auto_result_is_ready(conn):
            return

        audio_data = conn.asr_audio.copy()
        if len(audio_data) <= 15:
            return

        self._auto_stop_handled = True
        await self.handle_voice_stop(conn, audio_data)

    def _cancel_auto_stop_task(self):
        task = self._auto_stop_task
        if task and not task.done() and task is not asyncio.current_task():
            task.cancel()
        self._auto_stop_task = None

    def stop_ws_connection(self):
        self._cancel_auto_stop_task()
        if self.asr_ws:
            asyncio.create_task(self.asr_ws.close())
            self.asr_ws = None
        self.is_processing = False
        self._is_stopping = False

    async def _send_stop_request(self):
        """发送最后一个音频帧以通知服务器结束"""
        self._is_stopping = True  # 先标记为停止状态，阻止后续音频发送
        if self.asr_ws:
            try:
                # 发送结束标记的音频帧（gzip压缩的空数据）
                empty_payload = gzip.compress(b"")
                last_audio_request = bytearray(
                    self.generate_last_audio_default_header()
                )
                last_audio_request.extend(len(empty_payload).to_bytes(4, "big"))
                last_audio_request.extend(empty_payload)
                await self.asr_ws.send(last_audio_request)
                logger.bind(tag=TAG).debug("已发送结束音频帧")
            except Exception as e:
                logger.bind(tag=TAG).debug(f"发送结束音频帧时出错: {e}")

    def construct_request(self, reqid):
        req = {
            "app": {
                "appid": self.appid,
                "token": self.access_token,
            },
            "user": {"uid": self.uid},
            "request": {
                "reqid": reqid,
                "workflow": self.workflow,
                "show_utterances": True,
                "result_type": self.result_type,
                "sequence": 1,
                "end_window_size": self.end_window_size,
                "corpus": {
                    "boosting_table_name": self.boosting_table_name,
                    "correct_table_name": self.correct_table_name,
                }
            },
            "audio": {
                "format": self.format,
                "codec": self.codec,
                "rate": self.rate,
                "bits": self.bits,
                "channel": self.channel,
                "sample_rate": self.rate,
            },
        }

        # language参数仅在多语种模式下添加
        if self.enable_multilingual and self.language:
            req["audio"]["language"] = self.language

        logger.bind(tag=TAG).debug(
            f"构造请求参数: {json.dumps(req, ensure_ascii=False)}"
        )
        return req

    def token_auth(self):
        return {
            "X-Api-App-Key": self.appid,
            "X-Api-Access-Key": self.access_token,
            "X-Api-Resource-Id": self.resource_id,
            "X-Api-Connect-Id": str(uuid.uuid4()),
        }

    def generate_header(
        self,
        version=0x01,
        message_type=0x01,
        message_type_specific_flags=0x00,
        serial_method=0x01,
        compression_type=0x01,
        reserved_data=0x00,
        extension_header: bytes = b"",
    ):
        header = bytearray()
        header_size = int(len(extension_header) / 4) + 1
        header.append((version << 4) | header_size)
        header.append((message_type << 4) | message_type_specific_flags)
        header.append((serial_method << 4) | compression_type)
        header.append(reserved_data)
        header.extend(extension_header)
        return header

    def generate_audio_default_header(self):
        return self.generate_header(
            version=0x01,
            message_type=0x02,
            message_type_specific_flags=0x00,
            serial_method=0x01,
            compression_type=0x01,
        )

    def generate_last_audio_default_header(self):
        return self.generate_header(
            version=0x01,
            message_type=0x02,
            message_type_specific_flags=0x02,
            serial_method=0x01,
            compression_type=0x01,
        )

    def parse_response(self, res: bytes) -> dict:
        try:
            # 检查响应长度
            if len(res) < 4:
                logger.bind(tag=TAG).error(f"响应数据长度不足: {len(res)}")
                return {"error": "响应数据长度不足"}

            # 获取消息头
            header = res[:4]
            message_type = header[1] >> 4

            # 如果是错误响应
            if message_type == 0x0F:  # SERVER_ERROR_RESPONSE
                code = int.from_bytes(res[4:8], "big", signed=False)
                msg_length = int.from_bytes(res[8:12], "big", signed=False)
                error_msg = json.loads(res[12:].decode("utf-8"))
                return {
                    "code": code,
                    "msg_length": msg_length,
                    "payload_msg": error_msg,
                }

            # 获取JSON数据
            try:
                # 检查字节8-11是否为有效的JSON长度字段
                # 格式：4字节头 + 4字节序列号 + 4字节长度 + JSON数据
                length = int.from_bytes(res[8:12], "big")
                if length > 0 and length <= len(res) - 12:
                    # 有长度字段，从字节12开始读取指定长度的JSON
                    json_data = res[12:12 + length].decode("utf-8")
                else:
                    # 无长度字段或长度无效，尝试直接解析
                    json_data = res[8:].decode("utf-8")
                result = json.loads(json_data)
                logger.bind(tag=TAG).debug(f"成功解析JSON响应: {result}")
                return {"payload_msg": result}
            except (UnicodeDecodeError, json.JSONDecodeError) as e:
                logger.bind(tag=TAG).error(f"JSON解析失败: {str(e)}")
                logger.bind(tag=TAG).error(f"原始数据: {res}")
                raise

        except Exception as e:
            logger.bind(tag=TAG).error(f"解析响应失败: {str(e)}")
            logger.bind(tag=TAG).error(f"原始响应数据: {res.hex()}")
            raise

    async def speech_to_text(self, opus_data, session_id, artifacts=None):
        result = self.text
        self.text = ""  # 清空text
        return result, None

    async def to_playground_text(self, pcm: bytes) -> str:
        """Run a complete streaming ASR exchange for the virtual playground."""
        if not pcm:
            return ""

        ws = None
        try:
            ws = await websockets.connect(
                self.ws_url,
                additional_headers=self.token_auth(),
                max_size=1000000000,
                ping_interval=None,
                ping_timeout=None,
                close_timeout=10,
            )

            request_params = self.construct_request(str(uuid.uuid4()))
            payload = gzip.compress(json.dumps(request_params).encode())
            header = self.generate_header()
            header.extend(len(payload).to_bytes(4, "big"))
            await ws.send(header + payload)

            init_result = self.parse_response(await asyncio.wait_for(ws.recv(), 10))
            if init_result.get("code") not in (None, 1000):
                message = init_result.get("payload_msg", {}).get("error", "ASR 初始化失败")
                raise RuntimeError(f"ASR 服务初始化失败: {message}")

            chunk_size = max(320, int(self.rate * self.channel * self.bits / 8 * 0.1))
            for offset in range(0, len(pcm), chunk_size):
                chunk = gzip.compress(pcm[offset:offset + chunk_size])
                audio_header = self.generate_audio_default_header()
                audio_header.extend(len(chunk).to_bytes(4, "big"))
                await ws.send(audio_header + chunk)

            empty_payload = gzip.compress(b"")
            final_header = self.generate_last_audio_default_header()
            final_header.extend(len(empty_payload).to_bytes(4, "big"))
            await ws.send(final_header + empty_payload)

            text = ""
            deadline = asyncio.get_running_loop().time() + 10
            while asyncio.get_running_loop().time() < deadline:
                timeout = max(0.1, deadline - asyncio.get_running_loop().time())
                try:
                    result = self.parse_response(await asyncio.wait_for(ws.recv(), timeout))
                except (asyncio.TimeoutError, websockets.ConnectionClosed):
                    break
                payload_message = result.get("payload_msg") or {}
                result_data = payload_message.get("result") or {}
                if result_data.get("text"):
                    text = str(result_data["text"]).strip()
                for utterance in result_data.get("utterances") or []:
                    if utterance.get("definite") and utterance.get("text"):
                        text = str(utterance["text"]).strip()
                if payload_message.get("is_last") or payload_message.get("sequence") == -1:
                    break
            return text
        finally:
            if ws is not None:
                await ws.close()

    async def close(self):
        """资源清理方法"""
        self._cancel_auto_stop_task()
        if self.asr_ws:
            await self.asr_ws.close()
            self.asr_ws = None
        if self.forward_task:
            self.forward_task.cancel()
            try:
                await self.forward_task
            except asyncio.CancelledError:
                pass
            self.forward_task = None
        self.is_processing = False
