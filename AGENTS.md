# 项目开发约定

这是一套由 ESP32 固件、MQTT 网关、`zixuan-server`、`manager-api` 和管理控制台组成的 AI Plush Companion。修改功能时先确认它属于板级硬件、设备通用能力、网关协议还是服务端运行时，避免把一个设备的实现复制到另一块板子。

## 配置来源

板子的长期构建配置以 `firmware/main/boards/<board>/config.json` 为准。`sdkconfig_append` 是板级开关的持久化入口，发布脚本会据此生成构建目录中的 `sdkconfig`。不要把生成的 `firmware/sdkconfig` 或 `build/*/sdkconfig` 当作规范来源，也不要只手改生成文件。

通用功能的实现放在 `firmware/main`，板级代码只负责真实硬件差异，例如 GPIO、音频 Codec、屏幕、摄像头、按键和扩展芯片。新增板子时必须新建自己的 `config.json` 和板级目录，不能复制另一块板子的完整固件或整份 `sdkconfig`。

## 固件和硬件

不同板子即使都使用 ESP32-S3 和 16 MiB Flash，也可能使用不同的 I2S 引脚、采样率、屏幕驱动与方向、摄像头引脚、Codec、PCA9557 或按键逻辑。`zhengchen-cam` 和 `bread-compact-wifi-s3cam` 就是两套独立板级实现。任何固件发布都必须使用目标板子的 `BOARD_TYPE`，不得用另一块板子的整包固件替代。

板级功能改动后至少验证启动、屏幕横竖模式、音频输入输出、摄像头、按键和设备状态上报。摄像头不存在或初始化失败时，普通对话和其他 UI 功能仍应保持可用，不能因为空指针导致启动或首启动流程崩溃。

## 动态唤醒词

动态唤醒词是设备通用能力，但是否启用由每块板子的构建配置决定。需要启用的板子在自己的 `config.json` 中声明相应的 `CONFIG_USE_CUSTOM_WAKE_WORD`、默认唤醒词和阈值，并由 `firmware/scripts/build_default_assets.py` 生成布局 2 的 assets 镜像。

布局 2 的固定约束是 ESP32-S3、`assets` 分区至少 8 MiB、两个 3 MiB 槽位，能力报告必须为 `supported=true`、`layout_version=2`、`slot_size=3145728`。工厂 assets 镜像在分区尾部保留槽 A 和槽 B，默认资源写入槽 A，槽 B 保持擦除。应用固件和对应的工厂 assets 镜像必须成套发布；只刷应用固件不能把旧布局设备变成布局 2。

动态唤醒词的通用代码在 `firmware/main/wake_word_assets.*`，打包逻辑在 `firmware/scripts/build_default_assets.py`，按设备的服务端流程记录在 `docs/dynamic-device-wake-word.md`。设备只有在校验下载长度、SHA-256、容器、索引和 Multinet 初始化成功后才切换活动槽。失败时必须保留原活动槽和原唤醒词。

服务端只把设备已经确认生效的 `active_word` 放进连接配置。`desired_word` 不能直接进入在线对话配置。管理端显示的能力来自设备上报，不要为了让界面开放而手工修改数据库能力字段。

## 能力中心和设备工具

`manager-api` 是设备能力和 Skill 的控制面。Skill 只能组合已经登记的 Plugin、外部 MCP、角色 MCP 和设备固件上报的工具，不能上传或执行任意代码。Plugin 的可执行代码仍部署在 `zixuan-server`，管理端只保存执行器元数据、Schema、配置和密钥引用。MCP 密钥只在服务端内存中解析，不返回浏览器、设备或普通日志。

Skill 采用版本化发布和按设备绑定。已发布版本不可原地覆盖，设备只获得绑定的已发布 Skill。服务端运行时必须按连接隔离设备工具、Skill 工具和对话状态，不能因为某台设备有摄像头或某个工具而让其他设备获得该能力。

设备工具的能力来源是设备上报的 MCP 工具列表。摄像头工具需要同时具备设备工具和服务端视觉解释地址及令牌，MQTT 网关在设备初始化时同步服务端视觉能力。视觉请求可能耗时较长，设备指令接口必须使用足够的超时，并保留失败时的可读错误。

当设备没有绑定 Skill 时，服务端可以根据用户明确的设备操作意图临时暴露最小的设备工具集合，例如拍照、音量、亮度、主题和设备状态。不能因此绕过已绑定 Skill 的工具隔离。

## 记忆和对话配置

角色提示词、模型、Memory、Plugin、MCP 和动态上下文分别由各自的配置入口管理。设备级配置从 `manager-api` 加载后与本地运行时配置合并，不能用本地默认值覆盖明确的设备级设置。Memory 的外部存储和召回逻辑必须保持每个连接的会话隔离。

## 修改和验证

新增板子或固件能力时，先更新对应板子的 `config.json` 和相关规范文档，再生成固件与 assets。不要把能力只写在一次会话、数据库手工数据或生成目录里。

固件改动至少运行对应的 `firmware/tests`，并在真实设备上核对应用版本、板型、分区表、`wake_word` 能力报告和实际 UI。服务端或网关改动需要运行对应模块测试，并验证设备工具隔离、MCP 能力同步和失败路径。

完成动态唤醒词发布前，必须在真实布局 2 设备上验证正常下发、断网、错误哈希、无效索引、Multinet 初始化失败和切槽前断电。验证通过后才能把该板子的 `config.json` 标记为支持动态唤醒词。

## 刷写保护

- NVS 分区位于 `0x9000`，保存 Wi-Fi 凭据、设备 UUID、后台 token、绑定信息和用户设置。
- 不得把包含空白 NVS 的 `merged-binary.bin` 直接整片写入已绑定设备，否则会清空配网和绑定状态。
- 已绑定设备默认只刷 bootloader、partition table、ota data、应用固件和 generated assets，保留现有 NVS。确需整片刷写时，必须先备份 `0x9000` 的 NVS，并在刷写后恢复。
