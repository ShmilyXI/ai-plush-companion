# 服务端组件说明

服务端由四个职责清晰的模块组成。`manager-api` 是 Java 控制面，负责用户、角色、模型、音色、设备、能力版本、公共对话会话和 API Key。`xiaozhi-server` 是 Python 运行面，负责设备 WebSocket、VAD、ASR、LLM、TTS、记忆和工具隔离。`companion-console` 是当前 React 管理台，面向管理员维护控制面数据。`manager-mobile` 是移动端管理工程，面向后续移动端场景，不承担服务端运行时职责。

真实设备仍通过 MQTT 网关连接 Python 运行面。网关负责设备身份、MQTT/UDP 音频桥接和设备指令，不参与 APP 的公共对话协议。APP、小程序和网页先调用 Java 的公共会话接口创建短期会话，再使用返回的 runtime token 连接 Python WebSocket。API Key 只在 Java 边界解析，不进入 Python、设备或日志。

## 本地启动

进入 `manager-api` 使用 JDK21 和 Maven 启动 Java 服务。数据库变更由 Liquibase 管理，开发环境需要 MySQL 和 Redis。进入 `companion-console` 后执行 `npm ci`、`npm run dev`，默认开发端口为 8001，API 代理指向 Java 服务。Python 服务的依赖和私有配置位于 `xiaozhi-server`，不要把本地密钥提交到仓库。

## 配置边界

设备板级配置以 `firmware/main/boards/<board>/config.json` 为准。Python 的本地配置只提供默认值，明确的设备或角色配置由 Java 控制面下发后覆盖。模型运行配置可以包含服务端到 Python 所需的 provider 凭据，但不会进入浏览器、公共会话创建响应或设备协议。

## 公共对话接口

Java 提供 `POST /api/v1/conversations` 创建会话，以及角色、模型、音色和设备资源查询接口。第一方客户端使用用户 Bearer token，第三方服务端使用带 scope 和 Agent 白名单的 `ApiKey`。会话响应只包含短期 runtime token、会话标识、模式和流地址。

Python WebSocket 接收文字或完整音频输入，依次返回 `asr.final`、`llm.delta`、`tts.audio` 和 `turn.completed` 等事件。runtime bundle 保存在 Redis 并带过期时间，Python 只信任签名 token 和 Java 内部 bundle，不接受客户端提交的模型地址、提示词、插件或密钥。

## 验证

无声验证至少包括 manager-api conversation 测试、Python `tests/test_public_conversation_*.py`、MQTT gateway 测试和 companion-console 的 lint、构建、Vitest。真实设备验收另需确认串口 MAC、网关身份、ASR、LLM、TTS 和下行 UDP 的同一轮关联。夜间只做静态检查和不会输出声音的测试。
