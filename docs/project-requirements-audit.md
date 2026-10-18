# 项目需求逐项审计

这份审计对应最初的整体整理要求。状态只代表当前仓库证据，不把意图或未来计划当成完成。

## 设备对话链路

设备收音、VAD/Opus、MQTT/UDP、Python WebSocket、ASR、LLM、TTS 和设备下行已经有代码链路、历史真实设备基线和静音回归测试。2026-08-24 已补采真实设备记录，见 `docs/real-device-acceptance-2026-08-24.md`；本机地址切换、设备重启恢复、正常语音轮次、插话和网关恢复已有现场证据，失败 provider、动态唤醒词异常回滚、NVS 写入保护的独立故障场景仍未完成。

链路稳定性已有 TTS 失败 `LAST` 清理、bridge 半开关闭、readiness 屏障、liveness 布尔值、provider 超时、取消、并发、发送背压和断线任务回收。真实 barge-in、网关断开恢复、设备重启恢复和本机地址切换已有现场证据，断电、现场网络异常和真实 provider 故障仍需白天验收。

## 角色管理生效

系统提示词、性格、模型、音色、Memory namespace 和已发布 Skill prompt 已有 Java bundle、Python prompt 和失败降级测试。Skill 工具执行没有开放，避免公共 API 越过设备和工具授权边界。管理台页面与真实设备角色切换的最终验收仍待白天。

## 外部公共 API

文字和音频会话、流式 `llm.delta`、ASR final、TTS 音频、JSON base64、二进制音频、取消、历史、owner 校验和 API Key 已实现。OpenAPI 契约位于 `docs/public-conversation-api.yaml`。API Key 只在 Java 解析，支持哈希、撤销、过期、scope、Agent 白名单、审计和频率窗口。

角色、模型、音色和设备列表接口已经独立于设备 MQTT。模型和设备响应不返回 provider、MQTT 或 API Key 凭据。单轮文本、音频字节数、声明音频时长和输出字数限制已经实现，真实文字流客户端冒烟入口为 `scripts/public-conversation-smoke.mjs`；正式 APP 页面、音频客户端实联和按用户/Agent 的资源配额仍未完成。

## MQTT 与 APP 边界

MQTT 继续只服务真实设备身份、UDP 音频桥接和设备指令。APP、小程序和网页使用 HTTP 创建会话，再连接 Python WebSocket，不需要接入 MQTT。当前本机服务端的 `server.http`、`server.mqtt_gateway`、`server.ota` 和 `server.websocket` 已统一指向 `192.168.0.102`，但 bread 设备 NVS 仍保存旧地址。普通用户 APP 页面尚未实现，当前仓库提供的是可导入 API 契约和无声 WebSocket 客户端契约。

## 历史代码与前端清理

旧 `manager-web` 已删除，唤醒词资源已迁移到 Python runtime，Docker、部署文档和管理 API 测试已切换到 `companion-console`。仍未做全仓库任意历史 Python/Java 删除，因为这需要逐项引用、数据库兼容和运行回归证据，不能按名称盲删。

历史目录的引用和保留理由见 `docs/legacy-code-audit-2026-08-24.md`。当前已移除旧管理台残留构建目录和空的 `server/main/java` 目录，仍被测试、部署或运行时引用的工具保留。

## 固件与板型

`bread-compact-wifi-s3cam` 和 `zhengchen-cam` 已有长期 `config.json` 和静态能力测试。其他板型、动态唤醒词异常路径、分区/资产成套发布和真实刷写保护仍需逐板验收。夜间不执行刷写、串口、麦克风或扬声器操作。

## 通过条件

项目只有在白天真实设备时间线、角色矩阵、公共 API 客户端、失败路径、板型配置和 NVS 保护均有脱敏证据后，才可以标记整体完成。当前状态是代码、静音契约、管理台回归、本机地址切换后的真实设备正常轮次和 NVS 读取备份已覆盖（控制台 61 个文件、630 个测试通过；Python 全量 491 passed、31 个子测试通过），浏览器公共会话适配层已加入并通过语法检查，旧管理台残留构建目录已清理，但正式普通用户 APP、真实 provider 超时/失败现场、动态唤醒词故障回滚和 NVS 故障保护验收仍未完成。
