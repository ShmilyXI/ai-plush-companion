# Public Conversation Progress

## 已完成

阶段三 MVP 已加入 Java 控制面和 Python 运行面。`manager-api` 可以在用户身份下校验 Agent active version、解析有效模型和音色、创建会话并签发短期 HMAC runtime token。Python 可以校验 token、读取内部 runtime bundle、创建隔离会话，并通过 WebSocket 返回 `session.ready`、`turn.started`、`asr.final`、`llm.delta`、`tts.audio`、`turn.completed` 和错误事件。

当前第一方资源接口为 `/api/v1/agents`、`/api/v1/models`、`/api/v1/voices` 和 `/api/v1/devices`。模型响应只返回公开字段，不返回 `configJson` 或 provider secret。设备 MQTT 协议没有被外部会话接口复用。

协议、token、Java 会话服务、Python session、Python WebSocket handler、内部 bundle endpoint、资源 controller 和跨层身份一致性都有测试。JDK21 下 manager-api 编译通过，Python public conversation 测试 16 passed，连同 TTS、debug event 合计 73 passed；MQTT gateway 测试 8 passed，Node 语法检查通过。

## 当前限制

当前公开会话入口支持第一方用户 token 和受限的第三方 API Key。API Key 已使用独立表持久化，只保存 SHA-256 哈希和前缀，支持 scope、Agent 白名单、过期、撤销和最后使用时间。`ApiKey` 只在公开会话与资源路径进入 Shiro，其他管理、设备和内部路由仍拒绝该认证方式。原始 Key 只在创建响应中返回一次。

runtime bundle 目前保存在 manager-api 进程内存中，服务重启会使已创建的外部会话失效。公开 API 的 OpenAPI 文档、速率限制、配额、会话历史和真实 APP/Web 客户端联调也尚未完成。

音频 MVP 当前以 JSON 内 base64 音频块返回，后续需要在事件序列稳定后增加二进制 WebSocket 音频帧，并做音频大小、背压、断线恢复和取消的真实客户端验收。

## 下一切片

下一步补 API Key 审计、失败限流和 OpenAPI 脱敏验证，再做不依赖 MQTT 的外部 WebSocket 客户端回归。运行 bundle 的进程内存存储、二进制音频帧、断线恢复和真实 APP 联调仍未完成。完成这些验收前，不把 `/api/v1` 标记为公开生产接口。

完成 API Key 后，再补 OpenAPI 文档生成、速率限制、二进制音频帧和一个不依赖 MQTT 的最小 WebSocket 客户端回归。完成这些验收前，不把 `/api/v1` 标记为公开生产接口。
