# Public Conversation Progress

## 已完成

阶段三 MVP 已加入 Java 控制面和 Python 运行面。`manager-api` 可以在用户身份下校验 Agent active version、解析有效模型和音色、创建会话并签发短期 HMAC runtime token。Python 可以校验 token、读取内部 runtime bundle、创建隔离会话，并通过 WebSocket 返回 `session.ready`、`turn.started`、`asr.final`、`llm.delta`、`tts.audio`、`turn.completed` 和错误事件。

当前第一方资源接口为 `/api/v1/agents`、`/api/v1/models`、`/api/v1/voices` 和 `/api/v1/devices`。模型响应只返回公开字段，不返回 `configJson` 或 provider secret。设备 MQTT 协议没有被外部会话接口复用。

协议、token、Java 会话服务、Python session、Python WebSocket handler、内部 bundle endpoint、资源 controller 和跨层身份一致性都有测试。JDK21 下 manager-api conversation 测试通过，Python public conversation 测试当前为 19 passed；MQTT gateway 测试 8 passed，Node 语法检查通过。

## 当前限制

当前公开会话入口支持第一方用户 token 和受限的第三方 API Key。API Key 已使用独立表持久化，只保存 SHA-256 哈希和前缀，支持 scope、Agent 白名单、过期、撤销、最后使用时间、用户级活跃数量上限和来源失败窗口。创建、撤销、成功使用和失败认证写入脱敏审计。`ApiKey` 只在公开会话与资源路径进入 Shiro，其他管理、设备和内部路由仍拒绝该认证方式。原始 Key 只在创建响应中返回一次，非创建响应会省略 `createdSecret` 字段。

runtime bundle 目前保存在 manager-api 进程内存中，服务重启会使已创建的外部会话失效。公开 API 的配额、会话历史和真实 APP/Web 客户端联调仍未完成。OpenAPI 已补充 Bearer/API Key 方案、公共接口标签和一次性 Key 说明。

音频 MVP 当前以 JSON 内 base64 音频块返回，后续需要在事件序列稳定后增加二进制 WebSocket 音频帧，并做音频大小、背压、断线恢复和取消的真实客户端验收。

## 下一切片

下一步做不依赖 MQTT 的外部 WebSocket 客户端回归，并继续处理 runtime bundle 持久化、二进制音频帧、断线恢复、配额和真实 APP 联调。完成这些验收前，不把 `/api/v1` 标记为公开生产接口。
