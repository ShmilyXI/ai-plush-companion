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

## 尚未证明

这轮没有覆盖播放中插话是否稳定打断、断网重连、Python 重启后的设备自动重连、错误 TTS provider、ASR/LLM 超时、动态唤醒词失败回滚、NVS 保护和两个不同角色之间的现场切换。它们仍按 `docs/daytime-device-acceptance.md` 逐项执行，不能用本轮成功替代。
