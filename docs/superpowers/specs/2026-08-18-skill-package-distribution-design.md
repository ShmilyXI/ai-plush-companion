# Skill 分发包设计

## 目标

能力中心中的 Skill 改为真正可分发的包。管理员既可以上传 ZIP 包，也可以在线创建和编辑。两个入口最终生成相同的包结构，发布、下载、版本管理和设备绑定都以包为唯一事实来源。

Skill 包只编排已经登记的 Plugin、外部 MCP、角色 MCP 和设备工具。包不能携带或执行 Python、JavaScript、Shell、原生程序或其他任意代码。Plugin 的执行代码继续部署在 `xiaozhi-server`，设备工具继续由固件提供。

现有天气、新闻、搜索 Skill 和设备绑定无损迁移。设备和用户仍按原来的方式使用能力，不需要理解包格式。

## 包格式

正式包是 ZIP 文件，扩展名为 `.skill.zip`。ZIP 根目录必须包含 `skill.yaml` 和 `SKILL.md`，可以包含 `assets/` 和 `examples/`。根目录不能再套一层不确定名称的文件夹。

`skill.yaml` 是机器可读清单，第一版格式如下：

```yaml
schemaVersion: 1
id: skill-weather
name: 天气查询
version: 3
description: 查询指定城市的实时天气和预报
runtime:
  minVersion: 0.9.6
  responseMode: LLM
  timeoutMs: 30000
  failureMessage: 天气查询暂时不可用
  semanticThreshold: 0.7
triggers:
  - type: KEYWORD
    value: 天气
    priority: 100
    caseSensitive: false
    enabled: true
  - type: POSITIVE_EXAMPLE
    value: 深圳明天会下雨吗
    priority: 0
    caseSensitive: false
    enabled: true
tools:
  - type: PLUGIN
    ref: plugin-weather
    name: get_weather
    required: true
    defaults:
      location: 深圳
    overridableFields:
      - location
deviceRequirements: []
secretRefs:
  - api_key
assets: []
```

`id` 是包的稳定标识。创建后不能修改。`version` 是正整数，并且同一个 `id` 只能递增发布。`tools` 只能引用后台目录中已经存在并可用的工具。`secretRefs` 只声明需要的密钥名称，不保存密钥值。

`SKILL.md` 是模型执行说明。它保存完整的使用边界、执行步骤、参数解释、失败处理和回复要求。运行时将它作为当前 Skill 的执行提示词注入，不再单独维护一份数据库提示词。

`assets/` 只允许图片、文本、JSON、YAML 和 Markdown 等非执行资源。第一版资源只用于后台预览和运行时文本引用，不下发设备。`examples/` 保存测试话术和期望路由结果，可供导入校验和后台模拟验证使用。

## 标准 SKILL.md 兼容

后台允许导入只有 `SKILL.md` 的 Codex 或 Claude 风格目录包。导入器读取 Markdown front matter、标题和正文，生成待补全草稿。管理员必须补齐稳定 ID、版本、工具引用、触发规则和运行策略，后台随后生成正式 `skill.yaml` 和 `.skill.zip`。

兼容导入不是透传执行。外部包中的脚本、命令和未登记工具不会被接受。外部说明中无法映射的能力会显示为未解决依赖，解决前不能发布。

## 存储模型

新增 `ai_skill_package` 保存包版本元数据，包括 Skill ID、版本、包摘要、包大小、存储键、清单 JSON、Markdown 摘要、校验状态、创建者和创建时间。包二进制通过 `SkillPackageStore` 保存。第一版使用 manager-api 数据目录下的本地文件存储，路径由配置决定，接口保留 S3 兼容实现空间。

草稿仍可修改，但草稿的每次保存都会重新生成规范化 ZIP 和摘要。发布时把草稿包复制为不可变版本，并将包摘要写入 `ai_capability_version`。已发布包不能原地覆盖。同一内容重复发布不会生成不同摘要。

`ai_skill_definition`、`ai_skill_trigger` 和 `ai_skill_tool_mapping` 在迁移期保留为草稿查询投影。它们只能由包解析结果更新，不再作为独立写入入口。`ai_capability_version.content_json` 保存清单和 `SKILL.md` 的规范化运行时投影，保证现有设备有效能力接口无需传输 ZIP。

包删除采用引用保护。已发布版本被设备固定绑定时不能删除。Skill 停用不会删除包和历史版本。

## 后端服务

`SkillPackageParser` 负责安全解压、文件清单、YAML 和 Markdown 解析。`SkillPackageValidator` 负责 Schema、工具依赖、设备要求、密钥声明和版本规则。`SkillPackageBuilder` 负责把在线编辑数据生成确定性的目录和 ZIP。`SkillPackageStore` 负责保存、读取和删除二进制包。

管理接口增加包上传、草稿下载、已发布版本下载、包校验报告和兼容包导入。现有 Skill 创建与更新接口改为调用 `SkillPackageBuilder`。现有发布接口只发布当前已校验的草稿包。

上传采用 multipart 请求。服务端先写入隔离临时目录，完成校验后才移动到正式存储。任何失败都会清理临时文件，不创建半成品能力记录。

包校验报告包含清单错误、缺少文件、未知工具、不可用工具、未解决密钥、设备能力要求和兼容性警告。错误阻止保存或发布，警告允许保存草稿但发布前需要管理员确认。

## 安全约束

单包压缩后最大 10 MiB，解压后最大 30 MiB，文件数最多 200，单文件最大 5 MiB。拒绝绝对路径、`..` 路径、重复规范化路径、软链接、硬链接、加密 ZIP 和嵌套压缩包。

拒绝 `.py`、`.pyc`、`.js`、`.mjs`、`.cjs`、`.sh`、`.bash`、`.zsh`、`.exe`、`.dll`、`.dylib`、`.so`、`.jar`、`.class`、`.wasm` 和无扩展名可执行文件。MIME 类型和扩展名同时校验。

清单拒绝未知顶级字段，避免拼写错误被静默忽略。工具引用必须精确匹配 Plugin 执行器、已批准 MCP 工具或设备工具名。密钥值、Authorization、环境变量和连接配置不能出现在包或前端响应中。

Markdown 只作为文本提示词处理。后台预览进行 HTML 清洗，不允许脚本、内联事件、iframe 或外部可执行内容。

## 运行时

设备有效能力接口继续返回规范化 JSON，不返回 ZIP。每个 Skill 增加 `packageSha256`、`packageVersion` 和从 `SKILL.md` 提取的 `executionPrompt`。工具白名单、默认参数和触发规则来自同一已发布包投影。

`xiaozhi-server` 按设备配置版本缓存有效能力包。每轮对话选择 Skill 后注入对应的 `SKILL.md` 内容，并只暴露该包声明的工具。运行时不会执行包内文件，也不会读取未在清单登记的资源。

设备绑定继续支持 `LATEST` 和 `FIXED`。发布新包时，跟随最新版的设备配置版本递增。固定版本设备保持原版本。设备能力不满足 `deviceRequirements` 时，后台显示不可用原因，运行时不加载该 Skill。

## 后台界面

能力中心的 Skill 主操作改为上传 Skill 包和在线创建。列表显示当前草稿版本、已发布版本、包摘要、依赖状态和来源。每个版本可以下载原始包。

Skill 编辑器改为包编辑器。基础信息、触发规则、工具、默认参数和运行策略编辑 `skill.yaml`，执行说明编辑 `SKILL.md`。保存草稿时展示生成结果和校验报告。用户不需要手写 YAML，但可以查看只读清单预览。

上传流程展示包文件、解析结果、依赖映射和校验报告。只有 `SKILL.md` 的兼容包进入补全步骤。正式包校验通过后可以保存为草稿，再由管理员发布。

设备页面继续绑定 Skill 和版本，不改变使用路径。详情中增加包来源、包版本和依赖状态。Plugin 与 MCP 页面继续独立管理执行器和连接。

## 迁移

迁移服务遍历现有 `SKILL` 能力，根据 `ai_skill_definition`、`ai_skill_trigger`、`ai_skill_tool_mapping` 和最新版本内容生成规范包。包 ID 保持现有 Skill ID，包版本保持已发布版本号，设备绑定无需修改。

已发布历史版本分别生成不可变包。缺少完整历史字段时使用该版本的 `content_json`，不能使用当前草稿覆盖历史。生成后校验内容摘要，并记录迁移结果。迁移可重复执行，相同 Skill 和版本不会重复生成。

天气、新闻和搜索生成内置官方包，仍引用现有 Plugin。迁移完成后关闭旧 Skill 独立写接口。读取接口继续返回兼容字段，前端升级完成后再删除旧表单专用 DTO。

## 错误处理

上传失败返回稳定错误码和可读说明，不返回服务器路径或异常堆栈。未知工具、版本回退、摘要冲突、压缩炸弹和非法文件分别记录审计事件。

包存储不可用时不能发布。已发布包文件暂时不可读时，运行时仍可使用数据库中的规范化投影，但后台下载和重新校验会明确报错。投影摘要与包摘要不一致时版本进入损坏状态，不能绑定到新设备。

兼容导入无法确定工具时保存为未完成草稿，不自行猜测工具映射。设备缺少可选工具时只移除该工具，缺少必需工具时整个 Skill 对该设备不可用。

## 测试与验证

Java 单元测试覆盖合法包解析、确定性构建、路径穿越、压缩炸弹、软链接、禁用扩展、未知字段、未知工具、密钥泄漏、版本递增、发布不可变性、下载摘要和迁移幂等性。

接口测试覆盖 ZIP 上传、仅 `SKILL.md` 导入、在线创建、草稿更新、校验报告、发布、版本下载、权限、引用保护和错误码。

前端测试覆盖上传流程、兼容包补全、Markdown 编辑、工具映射、校验错误、发布确认、版本下载和设备绑定显示。

Python 测试证明运行时提示词、触发规则、工具白名单和默认参数全部来自包投影，未声明工具不会暴露，包内容不会作为代码执行。

端到端验证上传一个天气包，发布后绑定单台设备，验证天气话术命中并调用现有 `get_weather`。再导出该包、删除草稿、重新导入并发布下一版本，确认摘要、版本和设备跟随策略正确。新闻、搜索、音量、亮度和拍照链路继续可用。

## 完成标准

管理员可以上传、在线创建、校验、发布、下载和重新导入 `.skill.zip`。正式包包含 `skill.yaml` 和 `SKILL.md`，只引用现有工具，不执行任意代码。

Skill 的草稿、已发布版本、设备绑定和运行时投影都来源于包。旧 Skill 已生成等价包，天气、新闻、搜索及现有设备绑定没有丢失。

真实设备验证证明包发布后按设备生效，固定版本和跟随最新版正确，未绑定设备不会获得工具，密钥不会出现在包、浏览器、设备消息或普通日志中。
