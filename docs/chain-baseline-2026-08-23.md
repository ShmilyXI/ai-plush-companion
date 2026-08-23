# 2026-08-23 设备对话链路基线

## 基线范围

本记录对应阶段一，只记录当前实现和真实设备证据，不修改 MQTT、WebSocket、固件、provider、数据库或角色配置。采集开始于 2026-08-23 23:12（Asia/Shanghai），运行态补充采集至 23:19。基线来源提交为 `a0951e5`。

采集开始时工作区已有未跟踪产物，以及以下未提交修改：

```text
server/main/xiaozhi-server/core/playground/session.py
server/main/xiaozhi-server/core/providers/asr/doubao_stream.py
server/main/xiaozhi-server/tests/test_playground_session.py
```

这些修改不属于本次基线，不在本记录和后续提交中处理。

## 硬件与构建

串口设备为 `/dev/tty.usbmodem2101`，串口可以被 `screen` 以 115200 波特率打开。当前仓库可对应的构建目录是 `build/firmware-bread-compact-wifi-s3cam-display-aec`，另有一个 `headless-aec` 构建目录。这个构建对应的是历史或候选固件，不足以证明串口当前连接的设备就是该板。

显示版构建的已生成配置确认如下：

```text
target: esp32s3
board symbol: CONFIG_BOARD_TYPE_BREAD_COMPACT_WIFI_CAM=y
headless: CONFIG_BREAD_COMPACT_WIFI_CAM_HEADLESS is not set
custom wake word: CONFIG_USE_CUSTOM_WAKE_WORD=y
Chinese wake-word model: CONFIG_SR_MN_CN_MULTINET7_QUANT=y
English wake-word model: CONFIG_SR_MN_EN_NONE=y
console: UART0 at 115200, secondary USB Serial/JTAG enabled
project: xiaozhi 2.2.7
```

板级源目录是 `firmware/main/boards/bread-compact-wifi-s3cam`。该目录当前没有 `config.json`，只有 `config.h`、`compact_wifi_board_s3cam.cc` 和 README。这与项目约定中板级长期构建配置必须以 `config.json` 为准不一致，列为后续固件配置治理缺口。现阶段没有执行 flash、erase、write_flash、merge-bin 或 NVS 操作。

`config.h` 显示设备音频输入采样率为 16000 Hz，输出采样率为 24000 Hz，使用 simplex I2S，摄像头引脚和显示 SPI 引脚由板级代码定义。`compact_wifi_board_s3cam.cc` 在非 headless 构建中初始化 LCD、OV2640 兼容摄像头、按键和音频 Codec，并上报 `has_display=true`、`has_camera=true`。

## 串口现状

使用只读 `screen -L -S ai-plush-device /dev/tty.usbmodem2101 115200` 监听到运行中的设备状态日志：

```text
24 (21 KiB): RAM
I (1336849) SystemInfo: free sram: 40683 minimal sram: 29019
I (1346839) SystemInfo: free sram: 42483 minimal sram: 29019
I (1356839) SystemInfo: free sram: 40743 minimal sram: 29019
```

这证明串口对应的设备正在运行并持续输出系统状态，但本次监听没有触发设备重启，因此没有拿到启动区块、MAC、设备 ID、协议模式和当前应用版本的实时打印。构建产物的版本信息只作为静态证据，不能替代下一次正常重启后的启动日志。

网关运行态日志同时出现两台设备身份。历史会话中有 `bread-compact-wifi-s3cam`、MAC `9c:13:9e:8a:14:a4`；采集期间当前活跃的是 `zhengchen-cam`、MAC `7c:0c:5f:40:49:54`。因此本次不能把串口、网关设备身份和本地 bread 构建强行合并为同一台设备。下一步真实语音验收必须先确认串口启动日志里的 MAC 与网关身份相等。

## 服务状态

已知监听端口如下，来源为本机 `lsof` 和 Docker 容器状态：

```text
MQTT gateway: TCP 1883, node process
runtime-related: TCP 8000, 8003
manager or supporting services: TCP 8002, 9000
```

容器状态显示 manager-api、xiaozhi-server、Redis、MySQL 和 TencentDB Memory Core 均在运行。`http://127.0.0.1:8420/health` 返回 `200`，Memory Core 状态为 `ok`，向 `http://127.0.0.1:8000/health` 请求返回 `Server is running`。Python 容器重启后在 23:13:54 完成 VAD `SileroVAD` 和 ASR `ASR_DoubaoStreamASRV2` 初始化，并公布 WebSocket `ws://172.17.0.5:8000/xiaozhi/v1/` 和视觉解释接口。

manager-api 运行日志在 23:14 左右持续读取设备 `7c:0c:5f:40:49:54`、Agent `9e4a23e90532490e9faf94a111c0256d`、一个 TTS 音色资源和六类 Agent 模型配置。日志只作为字段来源和调用链证据，不把任何 Authorization 值或 provider 密钥写入本报告。

## 已确认的代码链路

设备音频由固件按 Opus 协议发送。MQTT 网关的 `WebSocketBridge.connect` 使用设备 ID、client ID 和 Bearer 签名连接 Python `/xiaozhi/v1/`，设备 UDP 音频在网关解密后封装为 WebSocket 二进制帧，Python 下行音频再由网关拆回 UDP。

Python `ConnectionHandler` 为每个连接维护 VAD、ASR、LLM、Memory、TTS、Agent 能力快照、会话历史和 `sentence_id`。`receiveAudioHandle.handleAudioMessage` 调用 VAD 和 ASR，识别结果进入 `startToChat`，随后由 `ConnectionHandler.chat` 读取记忆和当前 Skill 工具，调用 LLM 流式响应，将可见文本放入 TTS 队列。`sendAudioHandle` 负责 TTS 音频下行、流控、打断和 stop 消息。

Java `manager-api` 通过配置服务提供全局运行配置，并通过 `get_agent_models(device_id, client_id, selected_module)` 为连接获取设备绑定的私有 Agent 模型和配置。Python 在连接期间把 Agent 身份和能力 bundle 固定在连接对象上，新的 active 版本只应由新连接读取。

## 配置来源现状

静态代码已经证实以下配置路径：本地 `config.yaml` 与 `data/.config.yaml` 合并；当 `manager-api.url` 存在时，Python 通过 `get_config_from_api_async` 获取服务器配置，并把 `read_config_from_api` 设为真；连接建立后通过 `get_private_config_from_api` 并发获取 Agent 模型和纠错词，再覆盖当前连接的 VAD、ASR、TTS、LLM、VLLM、Memory、Intent、voiceprint 以及 Agent 身份相关配置。

以下运行时值尚未与一次真实设备对话关联，暂列为未验证：

```text
device_id and client_id from the live connection
session_id and sentence_id for a real turn
active Agent ID and active version
effective system prompt and personality fields
effective ASR, LLM, TTS provider and model IDs
effective TTS voice ID
memory namespace and Skill tool set
```

## 真实对话时间线

本节等待一次用户主动完成的真实语音对话。应使用固定短句，不触发设备工具，采集设备串口、MQTT 网关、Python runtime 和 manager-api 日志，再按 `device_id`、`client_id`、`session_id`、`sentence_id` 和 MQTT session ID 关联。当前网关日志能证明设备到网关的 UDP 音频转发和 WebSocket bridge 建立曾经发生，但没有一轮新的、可与当前串口身份对应的 ASR、LLM、TTS 完整时间线。历史会话只作为协议存在性证据，不计入本轮端到端通过。

容器内运行日志还保留了一条完整成功轮次：22:35:54 设备 `7c:0c:5f:40:49:54` 建立连接并收到 hello，22:35:58 ASR 得到最终文本，22:35:59 Python 把文本交给 LLM，22:36:00 下发第一段回复，22:36:01 火山双流 TTS 报告句子成功并发送 `LAST`。这证明当前配置组合在至少一条历史运行链路上可以完成端到端对话，但日志时间源和网关新会话没有完成同一轮关联，也不能作为 `3a637d0` 和 `1f0e886` 之后的回归证据。

修复后的真实硬件轮次已于 2026-08-24 00:02 完成。电脑扬声器播放唤醒词和测试句，设备 `7c:0c:5f:40:49:54` 经 MQTT 网关重新建立 bridge；Python 在 00:02:16 记录 hello，00:02:18 发送唤醒回复，00:02:22 ASR 得到“你好”，同秒进入 LLM，00:02:24 发送第一段回复，00:02:25 火山双流 TTS 报告句子生成成功并发送 `LAST`。网关同一会话随后持续收到下行 UDP 音频包，并在约 50.8 秒后结束会话。该轮发生在 `3a637d0` 和 `1f0e886` 之后，证明 TTS 失败清理和 MQTT bridge 重连修复没有阻断正常对话链路。

| 事件 | 层 | 时间 | 关联 ID | 结果 | 延迟 |
|---|---|---|---|---|---|
| 设备开始收音 | 固件 | 待采集 | 待采集 | 待采集 | 待采集 |
| 网关收到上行音频 | MQTT | 待采集 | 待采集 | 待采集 | 待采集 |
| Python 收到音频 | WebSocket | 待采集 | 待采集 | 待采集 | 待采集 |
| VAD 起止 | Python | 待采集 | 待采集 | 待采集 | 待采集 |
| ASR 最终文本 | Python/provider | 待采集 | 待采集 | 待采集 | 待采集 |
| LLM 开始响应 | Python/provider | 待采集 | 待采集 | 待采集 | 待采集 |
| LLM 首个可见文本 | Python/provider | 待采集 | 待采集 | 待采集 | 待采集 |
| TTS 首包 | Python/provider | 待采集 | 待采集 | 待采集 | 待采集 |
| 设备收到播放包 | 网关/固件 | 待采集 | 待采集 | 待采集 | 待采集 |
| 设备播放结束 | 固件 | 待采集 | 待采集 | 待采集 | 待采集 |

### 当前可用的链路证据

MQTT 网关历史日志记录了 `bread-compact-wifi-s3cam` 会话建立、UDP 序列号递增、音频包转发和会话结束，也记录了另一台 `zhengchen-cam` 会话的同样过程。Python 容器在本次采集期间只看到服务启动和模块初始化，没有看到新的设备对话事件。manager-api 日志看到设备心跳和 Agent 资源读取，但还没有把一次语音轮次的请求和响应串起来。

## 失败路径

现有代码和测试已经覆盖部分插话、音频延迟事件、连接工具路由和 MQTT hello 转发。23:17 和 23:21 的真实设备语音都进入了 ASR，并识别出“你好”，但随后火山双流 TTS 连续报 `发送TTS文本失败: send failed`，并清空 TTS provider WebSocket。此前异常分支没有保证向设备发送终止音频标记，已在 `3a637d0` 增加幂等的 `SentenceType.LAST` 清理，并由回归测试锁定。

这次复测还发现，重启 Python 容器后网关没有自动建立新的 Python WebSocket，设备仍处于旧网关连接状态，导致无法采集修复后的真实播报结果。后续验收必须把 Python 服务重启后的设备重连列为前置步骤，不能只看网关 MQTT 在线状态。

网关状态接口随后观察到 `exists=true`、`isAlive=null`，并且设备指令返回 `Connection closed`。这确认 MQTT 连接和 Python bridge 的生命周期此前没有联动。`1f0e886` 增加了 bridge 主动关闭标记，远端 bridge 断开时关闭 MQTT 协议连接，重复 hello、通话桥接和设备主动 goodbye 仍保留原有路径。网关测试已从 6 个增加到 7 个并全部通过。

| 场景 | 代码或测试证据 | 真实设备结果 |
|---|---|---|
| 播放中插话 | `tests/test_audio_barge_in.py`、`receiveAudioHandle.py` | 待采集 |
| MQTT/WebSocket 重连 | `mqtt-gateway/app.js`、`tests/test_start_local_config_sync.py` | 待采集 |
| LLM/TTS provider 超时 | provider 测试与 `ConnectionHandler.chat` 异常路径 | 待采集 |
| 长时间无声音 | `receiveAudioHandle.no_voice_close_connect` | 待采集 |
| 设备能力或 Agent 版本隔离 | `test_connection_tool_routing.py`、能力 bundle 测试 | 待采集 |

真实 TTS 故障目前只能确定到“ASR 完成后，TTS provider 发送阶段失败”，还不能从当前 INFO 日志判断是火山资源拒绝、凭据/音色组合问题，还是 provider WebSocket 生命周期问题。下一次设备重连后需要在不记录密钥和文本的前提下补充 provider close code/reason，或用脱敏的 provider 响应确认具体原因。

已使用当前 Agent 配置做过一次脱离设备的 TTS 最小请求。`seed-tts-1.0`、当前私有音色和火山双流端点可以建立连接并收到音频包，测试没有经过 MQTT，也没有向设备发送音频。因此“音色字段或资源 ID 直接无效”这一方向暂时排除，设备侧问题仍需在同一连接生命周期中复测。

## 自动化验证

阶段一计划中的现有链路测试已执行：Python 的音频插话、音频延迟事件和连接工具路由共 `10 passed`，有一个 Python 3.12 `audioop` 弃用警告；MQTT gateway 的启动配置、配置同步和 hello 转发共 `6 passed`。健康检查返回 Python `Server is running`，Memory Core 返回 `status=ok`。这些结果证明测试契约当前通过，但不能替代真实设备语音时间线。

## 基线结论

当前静态和运行态证据支持设备、MQTT 网关、Python 对话运行时和 Java 配置读取链路存在，且至少两台不同板型设备曾经通过同一网关工作。阶段一仍未完成：虽然已经拿到真实 ASR 证据、真实 TTS 失败证据和半开 bridge 根因证据，但串口当前身份、修复后的成功播报轮次、provider 关闭原因、跨层延迟和重连后的失败路径仍缺少与同一设备绑定的完整实测。

在这些证据补齐前，不删除历史代码，不新增生产外部对话 API，不改 MQTT 协议，不修改板级配置来源，不刷写设备。
