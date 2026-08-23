# AI Plush Companion 总链路路线图

## 目标

项目最终形成一条可观测、可恢复、职责分离的对话链路。设备收音后经过固件 VAD/Opus、MQTT 网关和 Python WebSocket 到 ASR，再进入角色配置、记忆和 Skill 约束下的 LLM，回复文字交给 TTS，音频经 Python、网关和 UDP 返回设备扬声器。APP、小程序和网页不伪装成设备，而是通过 Java 公共会话 API 获取短期 runtime token，再连接 Python 公共 WebSocket。

## 组件职责

`firmware` 只负责目标板硬件差异、收音、播放、显示、按键、摄像头、设备能力上报和设备协议。板级长期配置来自各板子的 `config.json`，不能把生成目录的 `sdkconfig` 当成规范。

`mqtt-gateway` 只负责设备身份、MQTT topic、UDP 音频桥接、设备指令和连接生命周期。它不解析角色提示词、模型凭据或公共 API Key，也不作为 APP 的对话协议。

`xiaozhi-server` 负责每连接的 VAD、ASR、LLM、TTS、Memory、Skill 工具隔离和事件流。设备连接与公共会话使用不同入口，但共享受控的模型和对话编排能力。

`manager-api` 负责用户权限、角色 active version、模型、音色、提示词、性格、设备归属、Skill 发布、公共会话、API Key 和 runtime bundle。provider 凭据只在受信任的 Java 到 Python 内部 bundle 中流转。

`companion-console` 是当前管理台。`manager-mobile` 是移动端管理工程。旧版 `manager-web` 已删除，唤醒词 Multinet 资源已经迁移到 Python runtime 自有模型目录。

## 已完成

设备与 MQTT、Python、Java 的历史真实链路已经建立基线，TTS 失败清理、bridge 半开连接、初始化 readiness、liveness 返回值等问题已有修复和回归测试。夜间阶段不重新播放声音，真实设备证据沿用基线文档。

公共对话 MVP 已支持文字和完整音频输入、ASR/LLM/TTS 事件、HMAC runtime token、Java 内部 bundle、角色/模型/音色/设备资源接口，以及不依赖 MQTT 的外部文字 WebSocket 契约。公共 WebSocket 还提供二进制音频控制帧和 TTS 二进制输出帧，JSON base64 仍保持兼容。

API Key 已支持一次性明文返回、SHA-256 哈希、scope、Agent 白名单、过期、撤销、最后使用时间、活跃数量上限、来源失败窗口和脱敏审计。API Key 只在公开会话和资源路径进入 Shiro，非创建响应省略一次性字段。runtime bundle 已使用 Redis TTL 存储。

旧管理台已从代码、Docker、部署文档和管理 API 测试中移除。新版 `companion-console` 已通过 lint、生产构建和 630 个 Vitest 测试。

## 角色配置验证矩阵

每个已发布角色需要沿同一条公共会话和设备会话分别验证。角色身份要与 active version 一致，系统提示词和性格字段要进入 LLM system message，模型配置要分别确认 ASR、LLM、TTS provider 和模型 ID，音色要与 TTS 模型匹配，输出模式要决定是否生成 TTS，Memory namespace 和 Skill 工具要按会话隔离。验证结果只记录角色版本、模型/音色 ID、事件序列和脱敏错误，不记录提示词原文、音频和 provider 凭据。

## 当前未完成

公共 WebSocket 已兼容 JSON base64 音频，并提供 `X-Audio-Transport: binary` 的输入控制帧和 TTS 二进制输出帧契约。LLM provider 的增量文字会在 provider 尚未结束时逐块发送。每个连接最多运行 2 个并发轮次、完成 100 个轮次，每轮有 60 秒处理超时，socket 发送有 10 秒背压超时，取消帧可在 provider 处理中到达，断开连接会回收未完成任务。公共 bundle 和 Python prompt 上下文不注入设备 MCP、Skill 工具或任意外部工具。Memory 已按 `public:<user>:<agent>:<conversation>` namespace 接入，查询或保存失败会降级而不撤销主回复。已发布 Skill 的触发规则和执行提示可以投影到公共 prompt，但 `toolNames` 被清空，Skill 工具执行仍未开放。会话创建已经按用户或 API Key 进入 Redis 频率窗口，公共连接提供最多 50 条已完成文字轮次的历史查询，Java 内部 server-secret 接口负责跨 Python 重启持久化；APP 也可用 owner-scoped REST history endpoint 查询。尚未完成单轮资源配额和断线恢复。普通用户 APP 页面和真实 APP 联调仍未完成。真实硬件的串口 MAC 与网关身份、VAD 独立时间点、可重复 barge-in 证据仍需在白天进行受控验收。

`bread-compact-wifi-s3cam` 和 `zhengchen-cam` 的长期 `config.json` 均已存在，并以静态测试锁定其板型、音频、摄像头、屏幕和唤醒词配置。其他板型仍需逐板补齐并验证启动、屏幕、音频、摄像头、按键、能力上报和动态唤醒词异常路径。历史 Python/Java 代码的删除必须以静态引用、模块测试和部署构建为门槛，不能按文件名直接清理。

## 下一阶段顺序

先完成公共 WebSocket 的二进制音频和断线/取消契约，再增加配额、会话历史和 APP 使用的资源/会话 API。随后对角色的提示词、性格、模型、音色、Memory、Skill 做逐项跨层验证。最后按板型补配置治理、清理剩余历史代码，并在白天用真实设备做一次完整证据采集。

## 验收规则

任何阶段只有在对应模块测试、跨层契约、失败路径和文档状态一致时才标记完成。真实设备测试必须确认目标板、MAC、固件版本、分区和 NVS 保护，禁止把另一块板的整包固件写入设备。夜间只运行静态检查、单元测试、契约测试和不会打开麦克风或扬声器的本地验证。

夜间可以运行 `scripts/verify-public-conversation-silent.sh`，它只执行 Java 公共对话测试、Python 公共对话与历史测试、两种摄像头板的静态配置测试和 `git diff --check`，不会启动服务、访问麦克风、访问扬声器或刷写设备。
