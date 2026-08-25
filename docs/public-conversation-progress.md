# Public Conversation Progress

## 已完成

阶段三 MVP 已加入 Java 控制面和 Python 运行面。`manager-api` 可以在用户身份下校验 Agent active version、解析有效模型和音色、创建会话并签发短期 HMAC runtime token。Python 可以校验 token、读取内部 runtime bundle、创建隔离会话，并通过 WebSocket 返回 `session.ready`、`turn.started`、`asr.final`、`llm.delta`、`tts.audio`、`turn.completed` 和错误事件。

当前第一方资源接口为 `/api/v1/agents`、`/api/v1/models`、`/api/v1/voices` 和 `/api/v1/devices`。模型响应只返回公开字段，不返回 `configJson` 或 provider secret。设备 MQTT 协议没有被外部会话接口复用。

可导入的公共接口契约在 `docs/public-conversation-api.yaml`，与 Java controller、API Key scope 和 WebSocket/历史入口保持同一版本说明。

协议、token、Java 会话服务、Python session、Python WebSocket handler、内部 bundle endpoint、资源 controller、跨层身份一致性和不依赖 MQTT 的外部文字 WebSocket 客户端都有测试。JDK21 下 manager-api conversation 测试通过，Python public conversation 测试当前为 39 passed；完整 Python 回归为 491 passed、31 个子测试通过，MQTT gateway 测试 8 passed，Node 语法检查通过。连接总时长会在 runtime token 有效期内受 15 分钟上限约束，单轮音频时长在客户端声明时限制为 60 秒，浏览器可以用 `bearer.<runtime-token>` WebSocket 子协议完成鉴权，重连时会重新校验主体、角色版本和输入输出权限。2026-08-24 已用运行中的 Java、Python 和 Redis 完成 Bearer、ApiKey、两个角色会话、流式文字和 history 的实联。

## 当前限制

当前公开会话入口支持第一方用户 token 和受限的第三方 API Key。API Key 已使用独立表持久化，只保存 SHA-256 哈希和前缀，支持 scope、Agent 白名单、过期、撤销、最后使用时间、用户级活跃数量上限和来源失败窗口。创建、撤销、成功使用和失败认证写入脱敏审计。`ApiKey` 只在公开会话与资源路径进入 Shiro，其他管理、设备和内部路由仍拒绝该认证方式。原始 Key 只在创建响应中返回一次，非创建响应会省略 `createdSecret` 字段。

2026-08-25 公共会话已支持角色 active version 绑定的天气和新闻只读 Plugin。Java bundle 只投影 `get_weather` 与 `get_news_from_newsnow`，Python 使用独立公共工具运行时执行，实时 WebSocket 会发送脱敏的 `tool.started`、`tool.completed` 和 `tool.failed` 事件，再把工具结果回灌给 LLM 生成文字和 TTS。设备控制、设备 MCP、角色 MCP、联网搜索和客户端自带工具仍不会进入公共会话。紫萱已激活到 Agent version 21，并绑定天气和新闻 Skill；真实文字测试已确认两类问题分别产生对应工具事件和基于工具结果的回复。

runtime bundle 和公共会话历史都已通过 Java 内部 server-secret 接口写入 Redis。runtime bundle 使用 15 分钟 TTL，历史保留最近 50 条文字轮次；APP 可通过 owner-scoped `GET /api/v1/conversations/{id}/history` 查询历史。manager-api 进程内存 map 只承担 bundle 同进程快速读取。会话创建已经按用户或 API Key 进入 Redis 频率窗口；单轮资源配额和正式 APP/Web 客户端联调仍未完成。OpenAPI 已补充 Bearer/API Key 方案、公共接口标签和一次性 Key 说明。

音频接口同时保留 JSON base64 兼容模式，并支持 `X-Audio-Transport: binary`。二进制模式用 `turn.audio.start`、WebSocket binary frames、`turn.audio.end` 组成一轮输入；TTS 先发不含音频数据的元数据事件，再发送 binary frame。LLM provider 的增量文字会在 provider 尚未结束时逐块发送。每个连接最多运行 2 个并发轮次、完成 100 个轮次，每轮有 60 秒处理超时，socket 发送有 10 秒背压超时，取消和断线回收已有无声契约测试；断线恢复和真实客户端验收仍未完成。

公共会话的 bundle 和 Python 对话上下文当前不注入设备 MCP、Skill 工具或任意外部工具。`device:control` 只作为后续独立设备命令 API 的 scope 预留，不能让公共对话直接获得设备工具。Memory 已使用按会话隔离的 namespace，查询或保存失败会降级为无记忆回复。已发布 Skill 的触发规则和执行提示可以进入公共 prompt，但 `toolNames` 被清空，Skill 工具执行仍未开放。

## 下一切片

下一步继续处理单轮资源配额、背压、断线恢复和真实 APP 联调。完成这些验收前，不把 `/api/v1` 标记为公开生产接口。
