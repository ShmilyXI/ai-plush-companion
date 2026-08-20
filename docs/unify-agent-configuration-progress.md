# unify-agent-configuration 实施记录

本文档记录 OpenSpec 变更 `unify-agent-configuration` 当前实现范围、验证证据和仍未执行的事项。文档结论以当前分支 `codex/unify-agent-configuration` 的代码、测试输出和 OpenSpec 任务清单为准。

## 已完成

Agent 配置已经形成草稿、不可变已发布版本、激活版本和激活审计记录。角色快照包含身份、提示词、模型、音色、记忆策略和 Skill 绑定。发布前会校验模型引用、Skill 引用、Skill 版本和凭据，已存在角色会执行初始版本回填。

Skill 所有权已经收敛到 Agent 版本。旧的设备 Skill 绑定可以迁移到 Agent 初始版本，并保留版本策略、固定版本、覆盖参数、触发优先级和启用状态。无法自动投影的 legacy 数据会写入冲突审计。新的管理台主流程不再提供设备级 Skill 编辑入口。

设备有效能力会先解析设备所属 Agent 的 active version，再根据设备硬件和设备 MCP 工具快照过滤 Skill 与工具。不可用 Skill 会返回具体原因，但不会进入 Python 可执行工具集合。Python 连接会固定连接期间的 Agent 版本，新的连接加载新的 active version，能力缓存支持版本失效和并发强制刷新合并。

角色编辑器已经聚合身份、提示词、模型、音色、记忆策略、Skill、绑定设备和版本发布操作。支持保存草稿、发布、激活、恢复历史版本、未保存修改保护、缺少凭据时内嵌配置全局模型资源，并且不会把已保存 secret 回填到页面。

设备记忆已经使用用户、Agent、设备组成的隔离 namespace。服务端和 Python provider 支持导出、导入、去重、备份、恢复、覆盖失败恢复和重试。管理台提供来源设备、目标设备、合并或覆盖模式、预览、结果统计、失败状态和重试操作。迁移限制在同一用户和同一 Agent 的设备范围内，源数据不会被删除。

管理台导航已经明确资源归属。普通用户使用角色、设备和记忆工作台，管理员使用模型管理和能力中心。设备详情只展示有效能力投影。导航测试会阻止重复 Skill 配置路径重新出现。

验收和发布门禁已经加入。包括 manager-api 定向测试、Python 契约测试、能力投影隔离测试、记忆迁移测试、模拟跨层测试、拒绝请求不变更矩阵、发布和激活竞态矩阵、能力刷新竞态、控制台 lint、TypeScript 构建、Vitest、Playwright、axe、键盘焦点、响应式溢出和视觉快照。模拟 rollout 脚本会检查 migration dry-run、parity、feature flag 切换和 rollback rehearsal。

## 尚未执行

真实 MQTT broker 链路没有执行，当前 MQTT 结果来自 gateway 模拟测试和契约测试。真实 ESP32 设备没有刷写或联调，未执行启动、屏幕、音频、摄像头、按键、设备状态上报和真实设备能力投影核对。生产或真实业务数据库没有执行 migration dry-run，也没有在真实数据上确认所有 Agent 初始版本、legacy 冲突处置和真实 parity。

真实 memory provider 外部服务没有执行，Mem0、PowerMem、TencentDB 的当前结果来自 provider 测试、失败注入和模拟链路。真实 MQTT 与 Python WebSocket 回滚后的健康检查没有执行，release evidence 中对应结果明确标记为 simulated-pass，不代表线上健康证明。

控制台完整 Agent editor 在 390px 下有一个 Playwright 场景被跳过。原因是 Ant Design Tabs 在该尺寸下内容面板可见性不稳定；同尺寸的登录、记忆迁移、设备能力、版本控制和模型凭据流程仍然执行并通过。构建仍有既有的 Rollup 大 chunk 和循环依赖警告，Python 测试有 `audioop` 弃用警告，这些没有导致门禁失败。

## 当前验证结果

统一脚本是 `scripts/verify-unify-agent-configuration.sh`。最近一次完整运行通过，manager-api 定向测试通过，控制台 lint、生产构建和 127 个 focused tests 通过，Playwright 15 passed、1 skipped，Python 74 passed 并包含 19 个 subtests，MQTT gateway 6 passed，模拟 rollout dry-run、parity 和 rollback 通过，OpenSpec validate 和 `git diff --check` 通过。

本记录和实现仍属于开发分支成果。推送到远程 `beta` 后，远程分支提交 SHA 以 GitHub 返回结果为准。
