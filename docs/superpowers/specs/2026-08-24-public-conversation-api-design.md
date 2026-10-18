# 统一外部对话 API 设计

## 目标

为 APP、小程序、网页和受授权的第三方服务提供与设备无关的文字和音频对话接口。外部调用与 ESP32 设备使用不同接入协议，但最终复用同一套 Agent 配置解析、记忆隔离、Skill 工具隔离、ASR、LLM 和 TTS 编排逻辑。

这项设计不改变现有 MQTT+UDP 设备协议，不让外部客户端伪装成设备，也不把 provider 密钥、任意代码或未发布配置交给调用方。

## 当前边界证据

`manager-api` 已经拥有用户 token、角色 Agent、已发布快照、模型资源、音色资源、设备和权限控制。Python `xiaozhi-server` 已经拥有每连接的 `ConnectionHandler`、ASR/LLM/TTS provider、Memory namespace 和 debug event。当前 `/xiaozhi/internal/playground` 只支持受保护的虚拟设备测试，不能直接作为公开对话接口。

## 推荐架构

### Java 控制面

`manager-api` 提供公开 API 的鉴权、资源授权和会话创建。它根据当前用户或 API Key 解析 Agent 的 active version，校验 Agent 是否可用、模型和音色是否可用、调用方是否有覆盖权限，然后签发短期运行令牌。

会话创建响应包含 `conversation_id`、`agent_id`、`agent_version`、允许的输入输出模式、过期时间和 Python runtime 的 `stream_url`。响应不得包含 provider 凭据、MCP 密钥、系统提示词原文或 Memory 服务密钥。

### Python 运行面

Python 增加面向外部会话的 WebSocket 流式入口。入口只接受短期运行令牌和 `conversation_id`，通过内部受信请求或签名校验获得已解析的 Agent 快照。它创建独立的外部会话对象，再调用现有会话编排核心，不复用设备 `ConnectionHandler` 的 MQTT、设备音频包和设备工具状态。

外部会话对象负责输入帧解析、事件序列、取消、超时、背压、音频编码和输出事件。Agent、Memory、Skill 和 provider 状态必须按 `conversation_id` 隔离。

### 设备边界

设备继续使用 MQTT+UDP 到 WebSocket 的现有桥接。设备身份、UDP 密钥、Opus 帧、设备 MCP 工具和设备控制不进入外部 API。APP 若要控制自己的设备，先通过独立的设备命令 API，由 Java 校验设备归属和命令权限。

## 鉴权模型

第一方客户端使用现有用户登录 token。Java 根据用户身份限制可用 Agent、设备和资源，返回短期运行令牌。第三方服务端使用可撤销、可过期、带 scope 的 API Key，API Key 只存哈希和元数据，原始值只在创建时显示一次。

运行令牌只包含不可变的 `conversation_id`、调用方主体、Agent ID、Agent version、允许的输入输出模式、过期时间和签名版本。Python 不接受客户端自带的 provider key、system prompt、Skill package、MCP endpoint 或任意模型 URL。

## 资源接口

资源接口由 `manager-api` 提供，并与对话流分开。接口使用统一分页和错误结构，返回公开元数据，不返回 secret 字段。

```text
GET /api/v1/agents
GET /api/v1/agents/{agent_id}
GET /api/v1/models?type=LLM|ASR|TTS|VAD|Memory
GET /api/v1/voices?tts_model_id={id}
GET /api/v1/devices
GET /api/v1/conversations/{conversation_id}
```

APP 只能看到当前用户可用的 Agent、模型和音色。第三方 API Key 只能看到 scope 授权范围内的资源。设备列表只返回调用方拥有或被授权的设备，不把设备 MQTT 凭据返回给浏览器。

## 会话接口

### 创建会话

```http
POST /api/v1/conversations
Authorization: Bearer <user-token-or-api-key>
Content-Type: application/json

{
  "agent_id": "agent-id",
  "input_modes": ["text", "audio"],
  "output_modes": ["text", "audio"],
  "voice_id": "optional-authorized-voice-id",
  "model_overrides": {}
}
```

`agent_id` 必填。APP 不提交模型覆盖；第三方只有在 API Key 包含 `conversation:override` scope 且资源属于授权范围时，才能提交 `voice_id` 或有限的非敏感模型覆盖。覆盖只影响当前会话，不改变 Agent active version。

### WebSocket 流

```text
GET wss://runtime.example/api/v1/conversations/{conversation_id}/stream
Authorization: Bearer <short-lived-runtime-token>
```

客户端发送 JSON 控制帧和文本帧，音频使用带 `request_id`、`sequence` 和 `final` 标志的二进制帧。服务端所有事件都带 `conversation_id`、`turn_id`、递增 `sequence` 和 UTC 时间戳。

服务端事件固定为：

```json
{"type":"session.ready","conversation_id":"c1","agent_version":4}
{"type":"turn.started","turn_id":"t1","input_mode":"audio"}
{"type":"asr.partial","turn_id":"t1","text":"你好"}
{"type":"asr.final","turn_id":"t1","text":"你好。"}
{"type":"llm.delta","turn_id":"t1","text":"你好呀"}
{"type":"tts.audio","turn_id":"t1","sequence":1,"mime_type":"audio/opus","data":"base64..."}
{"type":"turn.completed","turn_id":"t1","text":"你好呀。"}
```

失败、取消和超时分别使用 `turn.failed`、`turn.cancelled` 和 `session.expired`，错误对象只包含稳定的 `code`、可读 `message` 和可选 `retryable`，不包含 provider 原始凭据或完整上游响应。

## 输入与输出规则

文字输入在服务端直接进入 LLM 编排，仍执行角色提示词、Memory、Skill 和工具隔离。音频输入进入同一 Agent 的 ASR 配置，识别结果通过 `asr.final` 事件发送，再进入 LLM。LLM 文字增量可以先于完整回复发送；TTS 只对已确认的文本片段合成，音频块按序号发送。

客户端发送 `turn.cancel` 后，服务端必须停止该轮 ASR、LLM、TTS 和工具执行，并发送唯一的 `turn.cancelled`。同一 `request_id` 重复提交不得生成第二轮。连接断开后，服务端在有限时间内清空该会话的任务和音频队列。

## 配置解析

会话创建时 Java 固定 Agent active version 和允许的覆盖。Python 只接收解析后的非 secret runtime bundle，字段包含 Agent 身份、系统提示词引用、模型引用、音色引用、Memory namespace、Skill 工具集合和版本号。Python 不从本地默认配置覆盖 bundle 中已明确的字段。

对于设备会话，继续使用当前 `ConnectionHandler` 的设备能力投影和设备工具隔离。对于外部会话，默认不提供设备工具；只有调用方明确绑定自己的设备并拥有 `device:control` scope 时，Java 才向 Python bundle 注入最小设备工具集合。

## 超时、限流和审计

创建会话、单轮输入和整个连接分别有超时。服务端限制单连接并发轮数、单轮输入大小、音频时长、输出字数和总连接时长。API Key 支持按用户、Agent、scope、来源和速率限制。所有会话创建、覆盖尝试、工具调用、取消、失败和结束事件写入可脱敏审计记录。

日志和 debug event 使用 `conversation_id`、`turn_id`、Agent version 和 provider 名称关联，不记录完整音频、系统提示词、API Key、MCP 密钥或 Memory 内容。服务端在返回 `tts.audio` 前验证音频块序号和大小，发现乱序、过大或无效 base64 时结束当前轮并返回稳定错误。

## 兼容与迁移

现有 `/xiaozhi/internal/playground` 保持内部测试用途，不改成公开 API。新接口使用 `/api/v1` 版本前缀，第一版只新增能力，不修改 MQTT 设备协议和管理台现有接口。管理台后续可以把对话调试页迁移到新会话接口，但不以管理台页面作为协议实现。

## 验收标准

文字客户端可以引用一个已发布 Agent，收到流式 LLM 文字和完整会话结束事件。音频客户端可以上传一轮音频，收到 ASR 最终文本、流式 LLM 文字和有序 TTS 音频。取消、重复 request、过期 token、无权限 Agent、不可用模型、非法覆盖、断线和 provider 失败都有契约测试。

至少两个不同 Agent 的会话必须保持 prompt、model、voice、memory namespace 和工具隔离。设备 MQTT 链路的现有真实硬件回归必须继续通过。所有资源列表接口不返回 secret，第三方 API Key 可以独立撤销，运行令牌过期后不能继续发送音频或文本。

## 非目标

本阶段不实现计费、多人共享会话、实时语音通话、多模态视频流、任意模型代理、任意插件上传、公开 MQTT broker 和旧管理后台删除。这些需求需要独立设计和验收。
