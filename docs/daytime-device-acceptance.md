# 白天真实设备验收清单

这份清单只在白天执行。夜间只运行 `scripts/verify-public-conversation-silent.sh`，不要打开串口麦克风、扬声器或真实设备通话。

## 设备身份

先读取一次正常重启后的串口启动区块，记录 MAC、应用版本、目标板、分区表、NVS 是否保留、动态唤醒词能力和摄像头/显示能力。把串口 MAC 与 MQTT gateway 的 `clientId`、设备 ID 和 manager-api 设备记录逐字比对。当前已知网关活跃设备是 `7c:0c:5f:40:49:54`，不能直接推断它对应 bread 或 zhengchen，必须以启动日志为准。

## 固件与连接

确认刷写目标使用 `firmware/main/boards/zhengchen-cam/config.json` 或 `firmware/main/boards/bread-compact-wifi-s3cam/config.json`，并核对 `BOARD_TYPE`、16 MiB 分区、应用版本和 assets 镜像成套。已绑定设备只刷 bootloader、partition table、ota data、应用和 generated assets，保留 `0x9000` NVS；任何整片刷写先备份并恢复 NVS。

## 一轮完整对话

使用不触发设备工具的短句，记录同一轮的 `device_id`、`client_id`、MQTT session、Python session、`conversation_id`、`turn_id` 和 `sentence_id`。时间线必须能对齐设备开始收音、网关上行 UDP、Python ASR、ASR final、LLM first delta、TTS first audio、网关下行 UDP、设备播放结束和 `LAST`。

## 角色配置矩阵

选择两个不同的已发布角色，分别确认 active version、system prompt、personality、LLM/ASR/TTS model ID、voice ID、Memory namespace 和 Skill prompt。改变其中一个字段后创建新会话，旧会话不能被热修改。日志只记录 ID、版本和事件，不记录 prompt 原文、音频、API Key 或 provider secret。

## 失败路径

逐项验证 TTS provider 发送失败、ASR 超时、LLM 超时、设备断网、Python 重启、网关 bridge 断开、播放中取消、重复 request、音频过大、错误哈希和切换唤醒词时断电。每种情况都要有稳定错误、连接清理、原配置保留和可重试边界。TTS 失败必须发送幂等 `LAST`，bridge 断开必须关闭 MQTT 半开连接。

## APP/公共 API

用无设备的外部客户端先验证 Bearer 和 ApiKey 两种鉴权，再验证角色、模型、音色、会话创建、流式文字、二进制音频、取消和 owner-scoped history。确认原始 API Key 不进入 Python、WebSocket、MQTT 或普通日志。APP 不连接 MQTT，不提交 provider URL、prompt、Skill package 或设备凭据。

## 通过标准

只有当设备身份、完整时间线、两个角色配置矩阵、失败路径、公共 API 契约和 NVS 保护都有脱敏记录，才把项目标记为真实链路通过。任何一项缺证据都保持未验收，不用单元测试结果替代真实设备证据。
