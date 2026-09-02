# AI-Live Runtime Protocols

## Python WebSocket

公共会话和设备会话是同一 `ConversationRuntime` 的两个传输适配器。公共会话只使用短期 runtime token，设备会话只使用 `device-id`、`client-id` 和设备 Bearer 身份；两种身份不能互换。事件名称、`sequence`、`conversation_id`、`turn_id`、取消原因和心跳规则由公共会话契约统一定义，适配器不得把 provider 凭据、提示词或未校验工具定义放进请求。

地址为 `ws://<host>:8000/xiaozhi/v1/`。客户端需要发送 `device-id`、`client-id` 和 Bearer 认证头。连接后的业务帧是设备协议 JSON/二进制消息，认证失败会关闭连接。

APP、小程序和网页不要连接这个设备入口，也不要伪装成设备。公共会话先调用 manager-api 的 `/api/v1/conversations` 创建会话，再连接返回的 `/api/v1/conversations/{conversation_id}/stream`。服务端接受 `Authorization: Bearer <runtime-token>`；浏览器不能设置自定义认证头时，使用 `Sec-WebSocket-Protocol: bearer.<runtime-token>`。公共会话的文字和音频帧、ASR/LLM/TTS 事件与设备协议完全分开，音频客户端可以在 `turn.audio.start` 提供 `duration_ms`，超过 60 秒会被拒绝。

网页实时协议使用 `web.session.start` 作为首个控制帧，携带 `protocol_version: 1` 和协商后的 PCM 音频格式。服务端随后接受连续二进制 PCM 帧，客户端以 `input.audio.commit` 结束一段语音，以 `stream.stop` 结束连接。响应打断使用 `response.cancel`，服务端返回 `turn.interrupted` 后再发送唯一的 `turn.cancelled`。兼容客户端仍可使用 `stream.start`、`stream.audio.end` 和 `turn.cancel`。

网页事件保留统一的 `conversation_id`、`turn_id`、`sequence` 和 `occurred_at`，并在可关联时增加 `request_id`、`segment_id`、`event_id`。`session.ready` 会返回协议版本、输入输出模式、过期时间和 `continuous_audio`、`interruption`、`heartbeat` 能力。事件包括 `speech.started`、`speech.stopped`、`asr.partial`、`asr.final`、`llm.delta`、`tts.audio.chunk`、`tts.audio.done`、`turn.completed`、`turn.cancelled`、`turn.failed`、`session.expiring`、`session.heartbeat`、`session.pong` 和 `session.stopped`。请求失败会返回带 request、segment 或 event 关联字段的结构化错误。`X-Audio-Transport: binary` 时 TTS 事件先发元数据，再发对应二进制音频帧。

## MQTT

网关支持 MQTT 3.0 和 3.1.1。设备上行 topic 默认为 `device-server`，网关下行 topic 默认为 `devices/p2p/{mac}`。管理 HTTP API 的 Bearer 令牌为当天日期和 `MQTT_SIGNATURE_KEY` 拼接后 SHA-256 的十六进制结果。

设备 OTA 响应中的 `mqtt.client_id`、`mqtt.username`、`mqtt.password` 和 topic 是动态生成值，不能写死在客户端。
