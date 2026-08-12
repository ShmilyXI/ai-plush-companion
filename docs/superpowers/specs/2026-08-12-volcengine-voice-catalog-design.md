# 火山引擎音色目录与 TTS 精简设计

## 目标

精简管理端的声音管理和 TTS 模型管理。声音管理只保留火山引擎官方音色的浏览、筛选和试听。模型管理中的 TTS 只展示 Edge、火山引擎和阿里百炼。火山引擎支持在语音合成 1.0 与 2.0 之间切换，并按版本获取对应音色。

## 管理端界面

声音管理只显示音色库和音色克隆两个页签。音色资源页签、路由入口和相关跳转从界面移除。后端音色资源数据与接口暂时保留，避免破坏已有克隆记录和角色配置。

音色库改为只读火山音色目录。页面移除 TTS 模型下拉、新增、编辑和删除能力。页面提供模型版本下拉，仅包含语音合成 1.0 和语音合成 2.0。列表展示音色名称、音色编码、性别、年龄、语言、标签和描述。试听按钮播放火山返回的 `TrialURL`，无试听地址时显示不可试听。

模型管理的 TTS 列表、供应器选择和新增入口只展示 Edge、火山引擎、阿里百炼。对应保留的模型为 `TTS_EdgeTTS`、`TTS_HuoshanDoubleStreamTTS` 和 `TTS_AliBLStreamTTS`。其他 TTS 模型配置与供应器不从数据库删除，只在管理端相关查询结果中隐藏，避免现有引用立即失效。

## 火山引擎模型配置

火山引擎只保留 `TTS_HuoshanDoubleStreamTTS` 一个可见模型配置。配置增加模型版本字段，界面显示语音合成 1.0 和语音合成 2.0。保存时分别映射到 `seed-tts-1.0` 和 `seed-tts-2.0`，并写入现有 `resource_id`。

合成继续使用 `wss://openspeech.bytedance.com/api/v3/tts/bidirection`。现有 App ID 与 Access Token 鉴权保持不变。当前合成实现已经依据 `resource_id` 区分 1.0 和 2.0 的返回事件，无需拆成两套 TTS provider。

火山配置新增 Access Key ID 与 Secret Access Key，只用于调用 `ListSpeakers`。Secret Access Key 按现有敏感字段规则掩码处理，不返回明文给前端。

## 音色目录数据流

管理端向项目后端请求火山音色目录，并传入 `seed-tts-1.0` 或 `seed-tts-2.0`。后端读取统一火山模型配置中的 Access Key ID 和 Secret Access Key，为请求生成 HMAC-SHA256 签名，然后调用：

`POST https://open.volcengineapi.com/?Action=ListSpeakers&Version=2025-05-20`

请求体中的 `ResourceIDs` 只包含当前选中的版本。分页参数映射为火山的 `Page` 与 `Limit`。名称搜索在服务端对返回结果过滤，音色编码可通过 `VoiceTypes` 精确查询。

后端将 `Speakers` 转换成稳定的项目响应结构。音色唯一标识使用 `VoiceType`，试听地址使用 `TrialURL`，语言由 `Languages` 聚合，标签由 `NormalLabels` 与 `SpecialLabels` 合并。火山返回业务错误、鉴权错误或格式错误时，后端返回可读错误，不回退到 `ai_tts_voice`，避免界面展示过期数据。

## 现有 `/ttsVoice` 与数据库数据

`/ttsVoice` 是项目内部的音色增删改查接口，数据来自 `ai_tts_voice` 表。初始数据由数据库迁移脚本写入，之后也能由管理员手动维护。

改造后，声音管理的音色库不再调用 `/ttsVoice`，改用新的只读火山音色目录接口。`/ttsVoice` 和 `ai_tts_voice` 暂不删除，因为 Edge、阿里百炼、已有角色音色选择及其他旧页面仍可能依赖这些记录。本次只调整声音管理中的浏览和试听数据源，不改变角色配置保存音色的结构。

## 兼容与迁移

统一火山模型沿用 `TTS_HuoshanDoubleStreamTTS`，避免已有 1.0 角色的 `ttsModelId` 失效。数据库迁移把引用 `TTS_HSDSTTS_V2` 的角色、模板和音色克隆记录改为 `TTS_HuoshanDoubleStreamTTS`，随后隐藏 `TTS_HSDSTTS_V2`。迁移前统计引用，迁移过程保持幂等。

已有 `ttsVoiceId` 继续指向 `ai_tts_voice.id`。火山动态目录只供声音管理浏览和试听，不写入 `ai_tts_voice`，也不参与本次角色音色校验。Edge、火山和阿里百炼现有角色音色记录继续从数据库读取。

## 测试范围

后端测试覆盖火山签名请求、1.0 与 2.0 的资源映射、返回字段转换、分页、搜索、缺少凭证、火山错误响应和敏感字段掩码。模型查询测试验证 TTS 只返回三个允许项，同时不删除其他数据库记录。

前端测试覆盖声音管理只显示两个页签、音色库无增删改按钮、版本切换触发对应请求、试听行为、错误状态，以及模型管理 TTS 只显示 Edge、火山引擎和阿里百炼。

合成服务测试覆盖 `seed-tts-1.0` 与 `seed-tts-2.0` 使用相同 WebSocket 地址，并发送正确的 `X-Api-Resource-Id`。
