# TencentDB Memory 模型引用与本地配置设计

## 背景

`Memory_tencentdb` 当前要求重复填写记忆 LLM 与 Embedding 的地址、密钥和模型名。这会让模型管理出现多份凭据，已有 LLM 修改后记忆服务也不会同步更新。MemoryCore 同时缺少面向当前本地开发容器的完整配置入口。

本次调整让 TencentDB Memory 引用模型管理中的 LLM 与 Embedding 配置。MemoryCore 继续作为项目内独立服务部署，不作为模型供应器管理。

## 目标

模型管理新增 `Embedding` 分类，并提供 OpenAI 兼容的 Embedding 供应器。`Memory_tencentdb` 只保存被选中的 LLM、Embedding、MemoryCore 地址和 MemoryCore 密钥。manager-api 在每次模型代理请求中解析最新的被引用模型配置，因此引用模型更新后立即生效。

当前本地环境使用已配置的智谱账号。记忆 LLM 选择 `LLM_ChatGLMLLM`，Embedding 使用 `embedding-3`、1024 维并发送 `dimensions` 字段。该组合已经通过真实接口请求验证。

## 数据模型

新增模型类型 `Embedding`。首个供应器编码为 `openai`，配置字段包括 `base_url`、`api_key`、`model_name`、`dimensions` 与 `send_dimensions`。供应器只表示 OpenAI 兼容的 Embedding 接口，不绑定具体云厂商。数据库迁移创建通用的 Embedding 配置骨架，当前本地环境再写入智谱连接信息，不在迁移脚本中固化用户凭据。

`Memory_tencentdb.config_json` 新增 `llm_model_id` 与 `embedding_model_id`。新界面不再展示 `llm_base_url`、`llm_api_key`、`llm_model`、`embedding_base_url`、`embedding_api_key`、`embedding_model`、`embedding_dimensions` 和 `embedding_send_dimensions`。

为避免已有部署突然失效，运行时优先解析模型引用。引用不存在时继续读取旧字段。用户保存新版表单后写入模型引用，旧凭据字段不再由浏览器回传，也不会覆盖模型管理中的密钥。

## 后台界面

模型管理增加 Embedding 页签，交互沿用其他模型类型。TencentDB Memory 编辑表单中的记忆 LLM 和 Embedding 使用下拉选择，数据来自已启用的对应模型。

LLM 下拉只展示 manager-api 能解析为 OpenAI 兼容调用的配置。首期要求配置中存在有效的 `base_url`、`api_key` 和 `model_name`。Embedding 下拉展示已启用的 Embedding 配置。下拉选项显示模型名称，保存值为模型 ID，不向前端返回被引用模型的密钥。

模型连接测试同时验证 MemoryCore 健康状态、被引用 LLM 的最小对话请求和 Embedding 的最小向量请求。任一引用缺失、被禁用或配置不兼容时，返回具体的中文错误，不保存猜测出的替代配置。

## manager-api 解析流程

TencentDB Memory 设置服务先读取 `Memory_tencentdb`，确认已启用，再读取 `llm_model_id` 与 `embedding_model_id` 指向的模型配置。引用模型必须存在且启用，模型类型必须匹配。

LLM 解析器把选中模型的 `base_url`、`api_key` 与 `model_name` 转换成 MemoryCore 模型代理需要的运行参数。Embedding 解析器读取相同的通用连接字段，并额外读取维度和是否发送 `dimensions`。

模型代理接口保持不变。MemoryCore 仍调用 manager-api 的内部 OpenAI 兼容代理，代理再调用被引用的上游模型。修改任一被引用模型时，现有模型缓存失效机制会让下一次请求读取新配置。

## MemoryCore 本地部署

MemoryCore 使用仓库中已固定摘要的 Docker 镜像和命名卷。当前本地开发容器运行在默认 bridge 网络，`xiaozhi-server` 通过 `http://host.docker.internal:8420` 访问发布在本机回环地址的 MemoryCore。MemoryCore 通过 `http://host.docker.internal:8002/xiaozhi/internal/tencentdb-memory-model/v1` 调用 manager-api 模型代理。

`TENCENTDB_MEMORY_CORE_KEY` 使用独立随机密钥。`TENCENTDB_MEMORY_MODEL_PROXY_KEY` 使用当前后台参数 `server.secret`。两者不复用。服务器使用完整 Compose 部署时，MemoryCore 地址改为 `http://tencentdb-memory-core:8420`，数据继续保存在 `tencentdb_memory_data` 命名卷。

本地配置文件只保存到被 Git 忽略的环境文件，不提交真实密钥。后台保存 MemoryCore 密钥时继续使用现有凭据遮蔽与留空保留机制。

## 兼容与错误处理

旧版 TencentDB Memory 配置在模型引用为空时继续工作。新版配置引用的模型被删除或禁用后，模型代理返回配置错误，聊天服务按现有逻辑回退为无长期记忆，不影响基础对话。

Embedding 维度修改后需要重启 MemoryCore 并重新索引。LLM 地址、密钥、模型名以及 Embedding 地址、密钥、模型名的修改不要求重启服务。

## 测试范围

前端测试覆盖 Embedding 页签、TencentDB 两个模型下拉框、密钥不出现在选项数据中、保存模型 ID 和旧字段不再回传。

manager-api 测试覆盖模型引用解析、类型校验、启用状态校验、旧字段兼容、缓存更新、连接测试和代理请求整形。数据库迁移测试覆盖 Embedding 供应器、通用 Embedding 配置骨架和 TencentDB 新字段。

Python 与 Compose 测试继续覆盖 MemoryCore 地址、密钥、1024 维向量、分层记忆、设备与角色隔离、服务中断时聊天回退。完成后用本地真实容器验证 LLM、Embedding、L0 持久化和 L1 生成。

## 完成标准

后台能选择现有 LLM 与 Embedding，保存后立即生效。当前本地环境的 TencentDB Memory 连接测试通过，测试陪伴角色切换到 `Memory_tencentdb` 后能写入和召回记忆。项目重启后 L0 数据仍在，MemoryCore 停止时基础聊天仍可用。
