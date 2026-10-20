# 竞品调研与方向分析（2026-10）

> 调研范围：开源 AI 陪伴 / 语音对话 / 陪伴硬件 / 陪伴机器人项目，以及与本项目当前代码的对照。外部数据来自 2026 年 9–10 月的公开网页，Star 数等数字仅作量级参考。

## 一、结论先行

方向在技术上可行，而且项目已经走到了一半：`docs/public-conversation-api.yaml` 已经定义了与设备无关的会话接口和统一事件词表（`asr.partial`、`llm.delta`、`tts.audio.chunk` 等），`companion-web` 已经在用它做文字和实时语音，`manager-mobile` 也在路上。H5、App、Electron 本质上只是同一套接口的更多客户端，工程量是"做 SDK 和壳"，不是"重做架构"。

真正的问题不在可行性，而在差异化。"多端接入 + 可配置角色卡 / TTS / LLM + 长期记忆"这个组合在 2026 年已经是开源社区的标配，单凭它很难让用户选择你而不是 xiaozhi 生态或 AIRI。目前的两个特色里，"可配置声音、性格"基本没有差异化；"本地长期记忆"有潜力，但当前实现和这个宣传之间存在落差（见第三节），需要重新定义后才能成为卖点。

## 二、市面上的同类开源项目

下表按与本项目的重合度排序。

| 项目 | 定位 | 与你重合的部分 | 你可以借鉴 / 需要避开的点 |
|---|---|---|---|
| xinnan-tech/xiaozhi-esp32-server（你的上游） | ESP32 设备后端 + 智控台 | 几乎全部：多 LLM/TTS/ASR 供应商、角色、记忆（mem0、本地短期记忆）、声纹、知识库、MCP | 最新 v0.9.7（2026-09-29）。它是你最直接的"免费替代品"，用户会问"为什么不直接用原版" |
| joey-zhou/xiaozhi-esp32-server-java | 小智的 Java 企业级管理平台 | 音色定制、角色切换、对话记录、摘要式长期记忆（v4.1.0）、RAG | 和你的 manager-api 定位高度重叠 |
| hackers365/xiaozhi-esp32-server-golang | 小智的 Go 后端 | 声纹识别 + 按说话人切换音色、知识库、MCP | "按说话人区分"是家庭场景的关键能力 |
| akdeb/ElatoAI | ESP32 实时语音 AI 玩具/陪伴设备 | 角色管理、设备管理、Web 前端、对话历史；2026 年加入本地模型模式和 Pipecat 管线 | 和你"硬件陪伴 + 可配置角色"的定位最接近，偏海外市场 |
| StarmoonAI/Starmoon | 开源情感陪伴硬件 + 软件 | 陪伴硬件、Next.js 前端 | 与 Elato 同源思路 |
| moeru-ai/airi | 自托管虚拟伴侣（对标 Neuro-sama） | Web / 桌面 / 移动（PWA、Android）、多 TTS/LLM 供应商、实时语音 | 约 5 万 Star，社区极强。纯软件端的头号竞品 |
| Open-LLM-VTuber | 本地免提语音对话 + Live2D | 实时语音、打断、多供应商、跨平台 | 证明了"软件端语音陪伴"需求真实存在 |
| SillyTavern 及其生态 | 角色扮演前端 | 角色卡、TTS、多 LLM | 事实上的角色卡格式标准（Character Card V2/V3） |
| alicization / AILIS / spindl / Moli 等 | 桌面 AI 伴侣（Electron + Live2D/VRM） | 本地优先、长期记忆、主动对话 | 2026 年这一类大量涌现，说明"本地记忆 + 主动性"正在成为共识 |
| Pollen Robotics / Hugging Face Reachy Mini | 开源桌面陪伴机器人（$299） | 你未来想做的实体机器人 | 2026-05 上线 App Store，200+ 应用、400+ 动作；"应用商店 + 动作库"的平台模式值得重点研究 |
| 声网 Convo AI / AOSL、火山引擎 Mem0 | 商业语音与记忆基础设施 | RTC、ESP32 适配、托管记忆 | 量产厂商可能直接用它们，你要找它们不做的部分 |

xvxv-stack7/awesome-ai-companion 收录了更完整的清单，可以持续跟踪。

整体看，硬件侧被 xiaozhi 生态占住了"能用、便宜、可二开"的位置，软件侧被 AIRI 一类项目占住了"好看、好玩、社区大"的位置，机器人侧 Reachy Mini 已经在做"平台 + 应用商店"。没有哪一个项目同时把"一个角色在硬件、网页、App、桌面之间无缝迁移并共享记忆"做成核心体验，这是你目前架构里最接近空白的地方。

## 三、对两个现有特色的评估

### 1. "长期记忆，且完全存储在用户本地"

这个方向是对的，但目前的说法有三处需要修正。

第一，当前实现并不是"用户本地"。`zixuan-server/core/providers/memory` 下是 tencentdb、mem0ai、powermem、mem_local_short 等供应商，ADR 0006 规定记忆按"用户 + 角色"归属、由服务端统一管理，硬件与 App 共享同一份逻辑记忆。这是"服务端存储"，只有在用户自己部署整套服务时才算本地。对于普通用户，一套 Java + Python + MQTT 网关 + 多个前端的部署门槛很高，"本地"实际上很难落地。

第二，记忆只要进了提示词就会发给云端 LLM。如果模型来源是云端，"完全本地"在严格意义上不成立。宣传时需要区分"记忆的存储与所有权在本地"和"推理也在本地"，否则会被懂行的用户质疑。

第三，"本地"和"多端共享"天然冲突。你希望 H5、App、桌面、硬件共享同一份记忆，那这份记忆住在哪里就必须有答案：用户手机？用户电脑？家里的一台主机？

建议把这个特色从"本地存储"升级为"用户拥有的记忆"：记忆可查看、可编辑、可导出、可迁移、加密存储，默认放在用户自己的设备上，同步时端到端加密。这比单纯的"本地"更能讲清楚价值，也更适合未成年人、老人、亲密关系这些对隐私最敏感的场景，以及国内《个人信息保护法》和未成年人保护相关的合规要求。

一个具体的落地思路：让 Electron 桌面端（或一个家用小主机程序）同时充当"家庭中枢"，记忆库和可选的本地 ASR/TTS/LLM 跑在它上面，毛绒玩具、机器人和手机通过局域网或中继连接它。这样"本地记忆"和"多端共享"就同时成立了，桌面端也就不只是又一个聊天客户端。

### 2. "可以设置角色的声音、音色、性格"

这在 2026 年已经是基础能力。xiaozhi 三个版本的后端、ElatoAI、AIRI、SillyTavern 都支持，Go 版小智甚至能按说话人自动切换音色，声音克隆也已普及。它值得保留，但不能当作卖点，应该做到"必须有且体验好"，然后把差异化放在别处。

## 四、更有吸引力的方向建议

下面这些建议都围绕你最终想做的"实体工具型 / 陪伴型机器人"展开，而且大多能复用现有代码。

### 1. 把核心叙事定为"一个灵魂，多个身体"

角色（性格、声音、记忆、技能）是"灵魂"，毛绒玩具、桌面机器人、手机、网页、桌面宠物都是"身体"。用户白天在公司用桌面端和它聊，回家后毛绒玩具接着上午的话题继续，这种连续性是单端产品给不了的。你已经有了统一会话接口、按"用户 + 角色"共享的记忆、设备切角色的边界规则（ADR 0005），这个叙事几乎是现有架构的自然延伸，只是还没有被当成主卖点。

### 2. 从"存记忆"走向"有关系"

大多数竞品的记忆是"抽取事实、向量召回"。陪伴产品真正打动人的是关系感：记得上周你说要考试并主动问结果，知道你们认识了多久、一起经历过什么，能生成"我们的回忆"时间线或日记。可以考虑在记忆之上加一层关系状态（熟悉度、共同事件、待跟进事项），并驱动主动发起对话。2026 年桌面伴侣项目里"主动性"正在成为共识，硬件上做主动问候、提醒、跟进会更有感知。

### 3. 家庭多人记忆

实体机器人放在家里，面对的是一家人，而不是一个账号。当前模型是"用户 × 角色"，家庭场景需要"角色 × 家庭成员"：结合声纹识别（上游已有 3D-Speaker 方案）判断谁在说话，再按人召回记忆、调整语气和权限，比如孩子不能触发某些技能。这是硬件陪伴和纯软件陪伴拉开差距的地方，也是家长愿意付费的地方。

### 4. 角色卡里加入"表达层"

既然以后要做机器人，角色卡就不应只有提示词和音色，还应该包括表情、动作、灯效、屏幕动画、口头禅这类"身体语言"，并通过一个与硬件无关的表达协议映射到不同设备上：毛绒玩具上是屏幕表情，机器人上是点头和天线摆动，网页上是 Live2D。Reachy Mini 的"400+ 可复用动作"就是这个思路。同时建议兼容导入 SillyTavern 的 Character Card V2/V3，直接接入现成的角色生态，再在此基础上扩展你自己的字段（声音、表达、记忆策略、技能绑定）。

### 5. 工具型机器人走"技能 + 场景"

你已经有版本化的 Skill 中心和 MCP 工具隔离，这在同类开源项目里做得相当规范。往工具型方向走，可以围绕几个高频家庭场景打包技能，例如儿童学习陪伴、老人用药提醒与异常关怀、Home Assistant 家居控制，让"角色 + 技能包"成为可分享、可安装的单元，向 Reachy Mini 的应用商店模式靠拢。

### 6. 降低接入门槛，借力 xiaozhi 生态

你的固件基于 xiaozhi-esp32，市面上已经有大量兼容的开发板和成品。如果能让现有小智设备一键接入你的服务，这批设备就是现成的分发渠道。同时为 H5 / App / Electron 提供一个官方 JS SDK（以及一个可嵌入的语音组件），再提供"一条命令起服务"或"桌面端即服务"的部署方式，才能让"平台"真正被别人用起来。

## 五、需要尽早想清楚的取舍

第一是"平台"还是"产品"。做面向开发者的通用平台，你正面竞争的是 xiaozhi 生态、AIRI、Pipecat 这类有大社区的项目；做面向消费者的陪伴机器人，平台能力是你的内功，用户看到的是体验。建议把平台当作底座，对外先以一个具体产品和一个具体场景切入（例如儿童陪伴、独居老人或个人桌面陪伴），在一个场景里把"记忆 + 主动性 + 表达"做到明显好于竞品，再横向扩展。

第二是上游同步成本。项目已经和 xiaozhi-esp32-server 显著分叉，上游仍在高频更新（新模型、新 TTS、bug 修复）。需要决定是定期合并上游，还是只保留协议兼容、内部完全自研。各组件沿用原许可证（上游为 MIT）这一点 README 已有说明，商用前仍建议逐个确认。

第三是硬件本身。实体机器人的难点通常在供应链、BOM 成本、认证和售后，而不在软件。在硬件定型之前，用软件端和兼容的开发板验证"用户是否会持续回来聊"是成本最低的路径。

## 来源

- [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)，[Release v0.9.7](https://github.com/xinnan-tech/xiaozhi-esp32-server/releases/tag/v0.9.7)
- [joey-zhou/xiaozhi-esp32-server-java](https://github.com/joey-zhou/xiaozhi-esp32-server-java)
- [hackers365/xiaozhi-esp32-server-golang](https://github.com/hackers365/xiaozhi-esp32-server-golang)
- [78/xiaozhi-esp32 Releases](https://github.com/78/xiaozhi-esp32/releases)
- [akdeb/ElatoAI](https://github.com/akdeb/ElatoAI)
- [StarmoonAI/Starmoon](https://github.com/StarmoonAI/Starmoon)
- [KALO-ESP32-Voice-Chat-AI-Friends](https://github.com/kaloprojects/KALO-ESP32-Voice-Chat-AI-Friends)
- [moeru-ai/airi](https://github.com/moeru-ai/airi)，[AIRI Releases](https://github.com/moeru-ai/airi/releases)
- [GitHub Topic: ai-vtuber](https://github.com/topics/ai-vtuber)
- [awesome-ai-companion](https://github.com/xvxv-stack7/awesome-ai-companion)
- [Reachy Mini Apps](https://pollen-robotics-reachy-mini.hf.space/apps)，[VentureBeat：Reachy Mini App Store](https://venturebeat.com/technology/the-app-store-for-robots-has-arrived-hugging-face-launches-open-source-reachy-mini-app-store-with-200-apps)，[Pollen Robotics](https://pollen-robotics.com/reachy-mini/)
- [声网：2026 年开源语音 AI 爆发](https://www.shengwang.cn/blog/blogdetail/open-source-voice-ai-2026/)
- [火山引擎 Mem0](https://www.volcengine.com/product/mem0)
- [人人都是产品经理：2026 年的 AI 陪伴](https://www.woshipm.com/ai/6327120.html)
- [虎嗅：CES 2026 AI 硬件三大趋势](https://www.huxiu.com/article/4825694.html)
