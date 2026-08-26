# 真实设备链路验收记录

## 证据来源

本记录来自运行中的 Python `server.log`、MQTT gateway `gateway.log` 和 manager-api 访问日志。只保留设备、Agent、session、事件和时间信息，不复制 Authorization、provider 凭据、完整提示词、Memory 内容或原始音频。

设备 MAC 为 `7c:0c:5f:40:49:54`，网关 client ID 为 `zhengchen-cam@@@7c_0c_5f_40_49_54@@@7c_0c_5f_40_49_54`，Python 上报的设备板型为 `zhengchen-cam`，Agent 为 `9e4a23e90532490e9faf94a111c0256d`。Python 连接 session 为 `b5e3d2b0-6f03-496f-8ccd-b2aefbaa3430`。

## 成功轮次

该 session 在 2026-08-24 00:05:59 建立 Python WebSocket，收到 hello，协议版本为 2，音频格式为 Opus、16 kHz、单声道、60 ms 帧。manager-api 下发的有效配置包含 ASR、DeepSeek LLM、火山双流 TTS、TencentDB Memory 和当前角色身份；日志中的密钥均已脱敏。

设备在 00:05:59 上报唤醒词，Python 返回唤醒提示音并发送 `FIRST`、`LAST`。00:06:01 进入 realtime 收音，00:06:02 ASR 通道初始化成功。00:06:08 和 00:06:10 收到连续 ASR 片段，00:06:11 得到最终文本并交给 LLM。00:06:13 开始发送模型回复，00:06:14 TTS 报告句子生成成功并发送 `LAST`。之后又完成一轮结束语请求，00:08:18 至 00:08:20 连续生成多段 TTS，最后发送 `LAST`。

同一 session 的 MQTT gateway 日志显示 UDP 音频持续从设备转发到 Python，序列号连续增长到 2720，session 在 161.958 秒后正常结束。Python 随后记录超时检查退出、工具处理器清理、TTS/ASR 连接关闭和连接资源释放。

## 已证明

这轮证明真实设备身份、MQTT bridge、设备上行音频、Python ASR、LLM、TTS、设备下行音频和 `LAST` 清理可以在同一 session 里关联。当前 Agent 的角色配置、模型和音色资源也确实被 manager-api 读取并下发。

## 首轮尚未证明

首轮记录没有覆盖播放中插话、断网重连和 Python 重启后的自动重连。后续白天复测已经补齐其中的现场证据；错误 TTS provider、ASR/LLM 超时、动态唤醒词失败回滚、NVS 保护和两个不同角色之间的现场切换仍未完成。

## 2026-08-24 白天复测

09:00 至 09:05 通过主机扬声器连续触发真实设备。新建 session `59fad018-1b24-4e8b-a970-c70b50af6e34` 和 `26c2d235-216e-496d-9924-aecdb9cfd480`，均确认 MQTT gateway 建立 WebSocket、设备上行 UDP 序列连续、唤醒回复、ASR 最终文本、DeepSeek LLM 响应、火山双流 TTS 音频和 `LAST`。其中 `26c2d235-216e-496d-9924-aecdb9cfd480` 在重启 Python 服务后重新建立，证明服务重启后设备仍可重新发起会话。

复测发现聊天记录开关为 0 时，旧逻辑仍异步调用聊天标题接口，因没有历史记录而报 `Agent not found`。已在 `core/connection.py` 增加开关判断，并把 MemoryCore 保存改为当前事件循环内的消息快照和 5 秒有界等待。跨事件循环复用异步客户端的回归测试先复现 `RuntimeError`，修复后的针对性测试通过。09:12 至 09:17 的 session `6487e75a-c2f1-4a16-bccd-ef63fffbf8b7` 已确认 6 条 L0 消息写入 MemoryCore，L1 提取完成，Python 日志无记忆保存异常。

旧默认提示词包含 `weather_info`，但当前 Agent 没有天气 Skill，导致连接初始化时误调用带失效默认密钥的天气服务。已让提示词上下文只在 `get_weather` 函数实际授权时读取天气。09:15 至 09:17 的 session `e5a6527b-93d9-4409-912b-f39856915aa4` 重启服务后建立，日志中的工具列表仅有 `handle_exit_intent`，增强提示词正常完成，未再出现天气认证错误。

MemoryCore 元数据最初没有 manager-api 角色对应的 Agent，导致每次写入都降级到 legacy profile。已备份本地元数据库，并把 `9e4a23e90532490e9faf94a111c0256d` 与用户作用域团队 `ai-plush-companion:user:2086833405813424129` 建立 Agent、chat_memory 资产和固定绑定。09:23 的 session `97142419-708e-41aa-afea-b38182986b36` 完成普通对话，随后通过独立 `conversation/add` 验证写入不再出现 `ensureChatMemoryAsset` 或 `agent not found`；元数据查询返回同一 Agent 和团队。

09:35 对活跃的 zhengchen 设备执行网关进程断开。旧进程退出后由本机服务管理自动拉起，MQTT 1883 和 UDP 8884 恢复监听，gateway 随后重新收到 7c 设备客户端连接。该项证明 bridge 进程短暂中断后可以恢复；串口上的另一台 bread 设备 `9c:13:9e:8a:14:a4` 当前离线，设备保存的 MQTT 地址仍是旧主机 `192.168.0.110`，没有把它误当成当前活跃设备。

随后通过网关向 7c 设备发送 `self.res.esp_restart`。设备侧连接先关闭，gateway 重新建立 MQTT 客户端后，session `bfc98a7e-a52d-44da-aa30-33dd9b2acc30` 再次完成唤醒、ASR、LLM、TTS 和 `LAST`，证明设备重启后的恢复链路可用。

09:39 在 session `5f379c4a-d69f-4ae4-8894-1d81a8eecf24` 播放长故事期间发出新的唤醒词。旧 TTS 正在连续发送句子时收到 `Abort message received`，火山双流 TTS 明确记录终止文本处理线程，随后发送新的唤醒回复和新问题响应，旧轮没有继续发送 `LAST` 之后的故事内容。这是目前第一条可重复的真实播放中插话证据。

运行中的 manager-api 在重建 jar 后完成公共 API 实联。Bearer 创建的原角色会话返回 active version 20 和短期 runtime token；临时第二角色返回 active version 1。两个会话分别连接 Python WebSocket，均收到 `session.ready`、多段 `llm.delta` 和 `turn.completed`，回复内容分别体现原角色陪伴风格和临时角色的技术伙伴风格。一次会话还验证了 `conversation.history` 返回已完成轮次。临时角色随后已通过管理 API 删除。

同一轮还创建、列出、使用和撤销了受限 ApiKey。列表响应不含 `createdSecret`，授权会话创建成功，撤销后再次使用返回 401；Shiro 认证凭据匹配和运行时模型原始配置问题已修复，Python 公共 WebSocket 能正常使用 Java 下发的流式模型配置。

这次运行联调还修复了三个跨层缺陷：Agent 查询遗漏 `active_version_no`，角色可选字段为 null 时构建 bundle 崩溃，以及模型展示查询的脱敏配置被错误带入运行时。修复后 Java 公共会话返回 active version，原角色和临时第二角色都能实际流式对话，模型密钥只在受信任的 Java 到 Python bundle 内使用。

本轮已覆盖播放中插话、网关断开恢复、设备重启恢复、本机地址切换和公共 API 联调。仍没有覆盖 TTS/ASR/LLM 真实超时、动态唤醒词失败回滚、NVS 故障写入保护、两个角色现场切换和 APP/公共 API 客户端联调。MemoryCore 元数据迁移备份保存在本机 `.codex-tmp`，不纳入版本库；生产部署还需要把该 Agent/团队同步动作做成正式初始化或迁移流程，不能依赖手工数据库替换。

## 11:35 现场复测结果

通过主机扬声器播放“你好小智”和测试问题，随后等待 15 秒，没有产生新的 Python WebSocket、ASR、LLM 或 TTS 事件。网关状态接口显示 zhengchen 客户端对象仍存在但 `isAlive=false`，bread MAC `9c:13:9e:8a:14:a4` 不存在于本机网关；串口 bread 设备保存的 MQTT 地址仍为 `192.168.0.110`，本机网关地址为 `192.168.0.102`。本次不计入真实语音链路通过，后续需要先让目标设备连到本机网关并确认心跳恢复，再重复语音验收。

12:15 再次检查时状态未改变。串口日志仍显示设备周期性访问 `192.168.0.110:8002`，本机 MQTT gateway 仍没有 bread 客户端；zhengchen 状态仍为 `exists=true`、`isAlive=false`。期间没有执行刷写、擦除、NVS 写入或修改设备网络配置。

13:55 核对本机服务配置并完成地址统一。manager-api 数据库和 Redis 参数缓存中的 `server.http`、`server.mqtt_gateway`、`server.ota`、`server.websocket` 均指向 `192.168.0.102`，MQTT gateway、manager-api、Python runtime 和 MemoryCore 继续在本机健康运行。该操作只修改服务端参数，不会改变设备 NVS；bread 仍需通过人工恢复流程获取新地址。

## 14:11 设备 NVS 与本机重连复测

用户授权通过串口读取 NVS。esptool 识别芯片为 ESP32-S3，MAC 为 `7c:0c:5f:40:49:54`，板型随后由串口启动日志确认是 `zhengchen-cam`，不是此前误称的 bread 设备。已读取并保存完整 `0x9000` 至 `0xD000` 的 16 KiB 原始备份，SHA-256 为 `b410692cc8bbb4f2e9cd05325e54e4703324945ba3d03d08355df8a1f49ced01`，文件位于 `.codex-tmp/device-nvs-backup/zhengchen-7c0c5f404954-nvs-read-20260824.bin`。

解析 NVS 活动页发现 `ota_url` 已经是 `http://192.168.0.102:8002/xiaozhi/ota/`；旧的 `192.168.0.110` 只存在于较早的历史页副本。为避免重建 NVS 覆盖校准数据，本次没有写入 NVS，也没有擦除或刷写任何固件。

执行一次真实重启后，串口明确记录设备获得 IP `192.168.0.110`，随后访问 `192.168.0.102:8002`，并成功连接 `192.168.0.102:1883`。设备状态接口返回 zhengchen `exists=true`、`isAlive=true`。

同一连接 session `2456aebc-d7b2-4c3d-9aa1-c68c3deca071` 完成真实语音轮次。网关收到设备 hello 和 UDP 协商；Python 在 14:25:35 建立 MQTT bridge，读取 Agent `9e4a23e90532490e9faf94a111c0256d`、DeepSeek LLM、火山双流 TTS、豆包流式 ASR、TencentDB Memory 和角色音色配置。14:25:36 识别唤醒词“你好紫萱”并下发唤醒回复；14:25:43 ASR 得到完整用户文本；14:25:45 LLM 开始输出；14:25:47 至 14:25:48 火山双流 TTS 生成多段语音并下发；14:26:00 网关发送 TTS stop。该轮证明本机地址、MQTT、UDP、ASR、LLM、TTS 和设备扬声器下行已经重新打通。

14:29 之后再次唤醒同一设备，session `f78dd402-540b-4abf-8ebe-6a47d89a9cea` 成功建立，设备状态接口返回 `exists=true`、`isAlive=true`。本次播放中插话尝试没有形成新的可独立关联 `Abort message` 时间线，因此不替代此前已有的真实插话证据；正常重连和角色配置读取保持通过。

## 统一回归门禁

`scripts/verify-unify-agent-configuration.sh` 已在仓库 JDK21 下完整通过。manager-api 定向测试通过，companion-console lint 和 production build 通过，Playwright E2E `16 passed`，Python 能力/迁移测试 `74 passed`，MQTT gateway `8 passed`，模拟 rollout 的 parity、feature flag 和 rollback 通过，OpenSpec validate 与空白检查通过。
