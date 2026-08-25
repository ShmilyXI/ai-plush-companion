# 公共实时流式对话设计

## 目标

新增面向 APP、网页和小程序的实时流式公共会话接口，让客户端持续上传音频帧，服务端持续返回 ASR 局部文字、ASR 最终文字、LLM 增量文字和 TTS 音频块。现有按轮次的 `turn.audio.start`、二进制音频、`turn.audio.end` 协议继续兼容，不改变设备 MQTT 入口。

## 会话和控制帧

客户端仍先调用 Java `POST /api/v1/conversations` 创建会话，获取短期 runtime token 和流地址。实时 WebSocket 使用同一 token 和 `bearer.<runtime-token>` 子协议，但连接后先发送：

```json
{"type":"stream.start","request_id":"r1","audio":{"format":"pcm_s16le","sample_rate":16000,"channels":1}}
```

服务端确认后发送 `stream.ready`，然后客户端持续发送 16 kHz 单声道 PCM 二进制帧。客户端检测到本地停止说话时发送 `stream.audio.end`，服务端也使用服务端 ASR/VAD 的结束判断，二者任一确认都不能重复创建轮次。客户端发送 `turn.cancel` 可以取消当前 AI 生成和 TTS，但不能关闭 WebSocket。结束通话发送 `stream.stop`，服务端停止 ASR、取消未完成轮次、清空音频队列并关闭连接。

## 事件流

实时接口沿用现有事件字段 `conversation_id`、`turn_id`、`sequence` 和 `occurred_at`，新增或扩展以下事件：

```json
{"type":"stream.ready","details":{"sample_rate":16000,"channels":1}}
{"type":"asr.partial","turn_id":"t1","details":{"text":"深圳今天"}}
{"type":"asr.final","turn_id":"t1","details":{"text":"深圳今天天气怎么样"}}
{"type":"llm.delta","turn_id":"t1","details":{"text":"深圳当前"}}
{"type":"tts.audio.chunk","turn_id":"t1","details":{"mime_type":"audio/opus","chunk_index":0,"final":false,"data":"base64..."}}
{"type":"turn.completed","turn_id":"t1","details":{"text":"深圳当前天气晴朗。"}}
```

`tts.audio.chunk` 首包到达即可播放，`final=true` 表示该轮音频结束。工具调用继续使用 `tool.started`、`tool.completed` 和 `tool.failed`，结果仍只进入服务端 LLM 上下文。所有事件都按连接单调递增的 `sequence` 排序，音频块按 `chunk_index` 排序。

## 服务端流水线

Python 为实时连接创建独立的 ASR 流和输入音频缓冲区。音频二进制帧直接进入流式 ASR，稳定中间结果发送 `asr.partial`，VAD 或 `stream.audio.end` 确认一句话后发送唯一的 `asr.final` 并创建 `turn_id`。同一连接允许最多两个 AI 轮次并行；超过上限的已确认轮次排队，音频帧不因 AI 回复而停止接收。

每轮 LLM 使用现有角色、Memory、只读工具和权限 bundle。LLM 增量文字继续发送 `llm.delta`。TTS provider 必须提供异步音频块回调；每个块立即发送 `tts.audio.chunk`，不再在实时接口中等待完整 WAV。旧 provider 没有流式能力时，实时接口降级为单块 `tts.audio.chunk`，但仍保持事件格式一致并记录降级状态。

用户在 AI 播放期间开始说话时，服务端发送 `turn.cancelled` 或停止该轮 TTS，客户端同时把播放音量降到 20%。新轮次的首个 TTS 音频块到达后，客户端清理旧播放队列，只播放最新轮次；迟到的旧轮次音频块按 `turn_id` 丢弃。

## 限制和失败

单个音频帧、单轮最大时长、连接总时长、并发轮次和发送背压继续受现有限制。控制帧缺字段、采样率不支持、音频二进制出现在 `stream.start` 前、重复结束帧、未知轮次取消和乱序音频块返回稳定错误事件，不直接泄露异常堆栈。ASR、LLM、工具或 TTS 失败只结束当前轮，连接保持可用；`stream.stop`、token 过期或连接断开才结束整个流。

## 兼容和验收

现有普通对话页面和第三方客户端继续使用旧的按轮次接口。实时 Tab 首期切换到实时流式接口，显示 ASR partial/final、LLM delta、TTS 首包延迟、工具事件和连接状态。验收覆盖持续上传音频、首个 ASR partial、ASR final、首个 LLM delta、首个 TTS chunk、连续第二轮、AI 播放期间打断、旧轮次音频丢弃、工具调用、断线、取消和旧协议回归。
