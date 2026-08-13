# TencentDB Agent Memory 接入设计

## 目标

在现有记忆模型体系中新增 TencentDB Agent Memory。角色选择该模型后，陪伴对话使用 MemoryCore 的完整分层流程保存和召回记忆，同时保留现有管理台的查看、纠正、删除与清空能力。

MemoryCore 随当前项目通过 Docker Compose 部署。本地和服务器使用同一套部署结构。记忆提炼 LLM 与 Embedding 独立于实时对话模型，在后台统一配置，保存后下一次调用立即生效。

## 范围

本次接入 MemoryCore 的 L0 原始对话、L1 原子记忆、L2 场景记忆和 L3 用户画像。召回使用关键词与向量混合检索，并合并相关 L1、L2 导航和 L3 画像作为对话上下文。

本次不部署 Memory Hub、Memory Proxy、MemoryKnowledge、Skill、Wiki 和 CodeGraph。它们面向开发 Agent 团队，不属于陪伴产品的记忆链路。

## 总体架构

MemoryCore 作为独立容器运行，数据持久化到项目管理的 Docker volume。xiaozhi-server 新增 `tencentdb` Memory Provider，使用 MemoryCore v3 数据接口完成对话写入、分层召回与记忆管理。

manager-api 新增供 MemoryCore 访问的 OpenAI 兼容模型代理。代理提供 `/v1/chat/completions` 和 `/v1/embeddings`，使用现有 `server.secret` 鉴权。它不保存独立配置，每次请求通过现有模型配置缓存读取已启用的 TencentDB 记忆模型配置，再向实际供应商转发。模型保存、启停或删除会清除该缓存，后台修改普通连接配置后不重启任何容器，下一次请求使用新配置。

MemoryCore 只保存内部代理地址和 `server.secret`，不持有供应商密钥。供应商密钥继续使用现有模型配置的敏感字段保存与脱敏机制。MemoryCore 自身的 API 使用另一把独立密钥。

## 部署设计

在现有全量 Docker Compose 中加入 MemoryCore 服务。开发环境可单独使用同一服务定义启动。容器使用官方 `agentmemory/memory-core:1.0.0` 多架构镜像，并固定镜像摘要，避免 `latest` 漂移。该镜像对应上游仓库 v2.0.0 发布内容。

MemoryCore 使用 standalone 模式、本地 state backend、SQLite 存储与项目命名 volume。端口只暴露给 Compose 内部网络；需要本机诊断时通过显式开发覆盖文件映射到 `127.0.0.1:8420`。生产部署不对公网暴露。

MemoryCore 的网关配置启用 capture、L1 extraction、L2 scene、L3 persona 和 hybrid recall。LLM 地址指向 manager-api 内部模型代理的 chat 入口，Embedding 地址指向同一代理的 embeddings 入口。Skill 模块关闭。

Compose 为 MemoryCore 增加健康检查。xiaozhi-server 不把 MemoryCore 作为强启动依赖，记忆服务异常时实时对话继续运行，只记录记忆降级事件。

## 后台模型配置

数据库新增系统 Provider `tencentdb` 和默认模型 `Memory_tencentdb`。模型字段包含 MemoryCore 内部地址、MemoryCore 鉴权凭据、记忆 LLM 的 Base URL、API Key 和模型名、Embedding 的 Base URL、API Key、模型名、向量维度，以及是否发送 dimensions 字段。

LLM 与 Embedding 配置属于全局 TencentDB 记忆引擎配置。所有选择该记忆模型的角色共用同一组供应商模型，但数据按身份隔离。后台沿用现有模型管理页面编辑这些字段，API Key 使用密码控件与现有留空保留逻辑。

模型连接测试扩展到 TencentDB Memory。测试依次验证 MemoryCore 健康状态、LLM 代理调用和 Embedding 代理调用，并返回明确的失败环节。保存配置不触发容器重启。

## 身份与数据隔离

MemoryCore v3 使用 `team_id`、`agent_id`、`user_id` 和 `session_id`。映射规则固定如下。

```text
service_id = ai-plush-companion
team_id    = ai-plush-companion:user:{陪伴用户 ID}
user_id    = {陪伴用户 ID}
agent_id   = {陪伴角色 ID}
session_id = {当前会话 ID}
task_id    = {来源设备 ID}
```

MemoryCore v2.0.0 的 L2 和 L3 实际按 `team_id + agent_id` 隔离，不使用 `user_id`。因此 `team_id` 必须带用户命名空间，不能使用产品级固定值。`task_id` 负责保留设备来源，但不作为召回过滤条件或长期画像隔离维度。

因此不同用户和不同角色完全隔离。同一用户的多个设备绑定同一角色时，共享 L1、L2 和 L3 长期记忆；L0 原始对话仍能通过会话和设备来源追溯。设备切换不会丢失角色关系记忆。

现有 `memory_namespace` 继续作为对外不透明的兼容标识，但 Provider 不用它代替 v3 的显式隔离字段。

## 对话写入流程

对话期间仍由 xiaozhi-server 维护实时上下文。会话保存阶段，TencentDB Provider 将尚未写入的用户与助手消息转换为 MemoryCore v3 Conversation Add 请求。请求包含完整隔离字段、当前 session ID、消息角色、内容、时间与设备来源。

MemoryCore 先写入 L0，再由内部异步 pipeline 触发 L1 提取、去重、L2 场景归纳和 L3 画像更新。xiaozhi-server 不等待 L1 到 L3 完成，不让记忆提炼增加语音会话关闭延迟。

Provider 只在会话结束时提交一次完整会话。网络结果不明时，先查询该 session 的 L0 尾部并比较角色、内容和时间，再决定是否重试，避免把同一轮重复写入。MemoryCore 返回的 accepted IDs 用于诊断和审计，不假设上游支持客户端幂等键。

## 召回流程

每轮调用实时 LLM 前，Provider 使用当前用户问题分别调用 MemoryCore v3 的 Atomic Search、Scenario List 和 Core Read。所有请求携带完整隔离字段，不能使用缺少用户与角色隔离维度的旧 `/recall` 简化接口。

Atomic Search 返回 L1 混合检索结果。Scenario List 返回 L2 场景摘要与路径，只注入导航摘要，不在每轮读取全部场景正文。Core Read 返回 L3 用户画像。Provider 将三层结果整理成现有 `query_memory` 返回的文本上下文，并分别设置条数和字符预算，避免记忆占满实时模型上下文。

召回设定短超时。超时、MemoryCore 故障或 Embedding 故障时，本轮退化为无记忆回答。Embedding 不可用时，MemoryCore 退化为 BM25 关键词检索，不中断对话。

## 记忆管理

现有用户与管理员记忆页面保持不变。列表读取 L1 原子记忆，展示内容、更新时间、所属角色和来源设备。写入 L0 时把设备 ID 放入 `task_id`，MemoryCore 会把它传播到 L1 并在查询结果中返回。纠正操作映射到 Atomic Update。单条删除映射到 Atomic Delete。清空操作先枚举并删除当前用户与角色范围内的 L0 和 L1，再逐项删除 L2 场景并把 L3 画像写为空内容。任一步失败都返回失败，并允许重复执行完成剩余清理。

现有管理接口按设备进入，但实际长期记忆范围是用户与角色。界面会把清空文案改为清空该设备当前绑定角色的长期记忆，避免误导用户以为共享角色记忆只属于一台设备。

切换到 TencentDB 记忆模型不会自动迁移原记忆模型中的数据。第一版保留旧数据，不双写，也不删除。切回原模型后仍能使用原数据。后续如需迁移，单独设计可审计的导入流程。

## 内部模型代理

代理路径使用现有 `server.secret` Bearer 鉴权。生产部署只让 MemoryCore 通过 Compose 内部地址调用。它拒绝客户端提供的任意上游地址和密钥，所有目标配置都从服务端数据库取得，防止成为开放代理。

Chat Completions 请求保留 MemoryCore 需要的消息、温度、最大 token、工具和响应格式字段，再使用后台配置覆盖模型名与供应商鉴权。Embeddings 请求保留 input，使用后台配置覆盖模型名，并按配置决定是否发送 dimensions。

代理复用现有模型配置缓存降低数据库压力，不增加第二层缓存。模型配置保存、启停或删除时主动清除缓存，因此变更对下一次请求生效。代理日志不记录 Authorization、API Key、完整消息、完整 Embedding 输入或完整响应正文。

## 错误处理与可观测性

Memory Provider 初始化失败不会阻止设备建立连接。保存失败不回滚聊天记录。召回失败不阻止主 LLM 回答。管理操作失败必须返回失败，页面保留原内容，不能假装删除成功。

现有设备调试事件继续记录 memory query 与 save 的开始、完成、耗时和失败类型。新增 MemoryCore 请求 ID、召回策略、命中层级、命中数量和降级原因，不记录完整记忆正文。manager-api 记录模型代理的供应商、模型、耗时、状态码和请求类型，敏感内容脱敏。

## 安全与数据生命周期

MemoryCore 不暴露公网端口。xiaozhi-server 使用独立的 MemoryCore 密钥访问它。MemoryCore 调用 manager-api 模型代理时使用现有 `server.secret`，两把密钥不能相同。凭据通过部署环境注入，不提交到仓库。

删除单条记忆只影响指定 L1 记录。清空角色记忆删除该隔离范围内的所有分层资产。卸载或升级容器默认保留 volume。只有明确执行 purge 命令才删除记忆数据，并在操作说明中标注不可恢复。

## 测试与验收

Provider 单元测试覆盖隔离字段映射、消息转换、完整分层召回格式、超时降级、重复写入防护、列表、纠正、单删和全清。manager-api 测试覆盖配置读取、缓存失效、Chat 与 Embedding 转发、密钥保留、内部鉴权和开放代理防护。

集成测试使用伪 MemoryCore 或录制契约验证 v3 请求与响应。Docker 冒烟测试验证 MemoryCore 健康检查、数据写入、容器重启后数据仍在，以及关闭 Embedding 时回落到 BM25。

验收场景是两个用户、两个角色和两个设备产生带明显差异的对话。召回不得跨用户或跨角色；同一用户同一角色换设备后应召回共享长期记忆；后台修改 LLM 或 Embedding 模型后，下一次代理调用必须使用新配置；MemoryCore 停止时语音聊天仍可回答，记忆管理明确报错。

## 发布方式

新 Provider 默认启用为可选模型，不自动切换现有角色。先在本地完成完整链路，再选择少量测试角色启用。观察召回耗时、命中率、错误记忆和模型费用后再扩大范围。

回滚只需把角色切回原记忆模型并停止 MemoryCore。MemoryCore volume 保留，回滚不会损坏已产生的数据。
