# 豆包语音 SeedTTS 2.0

角色可以用豆包语音（火山引擎 SeedTTS 2.0）说话。本文记录它的接入方式、密钥放在哪里，以及网页端怎么选到它的音色。

## 接入方式

豆包语音走 OpenAI 兼容的 `POST /v1/audio/speech` 网关，模型是 `seed-tts-2.0`，请求带一把 Bearer 密钥。运行时是 `zixuan-server` 的 `core/providers/tts/doubao_seedtts.py`，供应器类型 `doubao_seedtts`。

它和已有的两个火山引擎供应器不同：

| 供应器 | 接口 | 凭据 |
|---|---|---|
| `doubao` | 火山引擎 v1 HTTP 接口 | appid、access_token、cluster |
| `huoshan_double_stream` | 火山引擎双向流式 WebSocket | appid、access_token、resource_id |
| `doubao_seedtts` | OpenAI 兼容网关 `/audio/speech` | 网关地址和一把 Bearer 密钥 |

前两个需要火山引擎控制台的应用凭据。只有网关密钥时用 `doubao_seedtts`。

## 模型和音色

Liquibase 变更 `202611241500` 登记了：

- 供应器 `SYSTEM_TTS_doubao_seedtts`，可配置字段只有模型、默认音色、语速、音频格式和输出目录；
- 模型 `TTS_DoubaoSeedTTS2`（豆包语音 SeedTTS 2.0），默认音色 `zh_female_vv_uranus_bigtts`，输出 mp3；
- 14 个中文音色 `TTS_DoubaoSeedTTS2_01` 到 `_14`，9 个女声、5 个男声，每个都在网关上实际合成过。

音色必须是 SeedTTS 2.0 的音色（`*_uranus_bigtts`）。1.0 时代的 `*_moon_bigtts`、`BV*_streaming` 和这个资源 ID 不匹配，网关会返回 `55000000 resource ID is mismatched with speaker related resource`。要加音色时，先用一句中文在网关上试合成，确认能出声再写进新的变更脚本。

## 密钥只在 zixuan-server

平台的网关地址和密钥只从 `zixuan-server` 的环境变量读取：

```
ZIXUAN_DOUBAO_TTS_API_URL=https://<网关>/v1
ZIXUAN_DOUBAO_TTS_API_KEY=<密钥>
```

部署时写在 `/etc/zixuan/zixuan.env`（见 `deploy/zixuan/zixuan.env.example`），改完重启 `zixuan-server`。两项缺一项时，合成会失败并在日志里提示缺了哪个变量，不会打印密钥。

数据库、Liquibase 脚本、管理台、浏览器和设备里都没有这把密钥：

- 供应器没有凭据类字段，所以管理服务把这个模型当作“无需配置”，角色可以直接选，也不会要求用户填自己的密钥。
- 平台密钥只发往环境变量里的地址。模型配置或角色覆盖项里的 `api_url` 改不了平台密钥的去向；只有配置里同时给了自己的 `api_key` 和 `api_url`，才按这一对发请求。
- 合成失败时，错误信息只带 HTTP 状态和网关返回正文的前 200 个字符，不带请求头。

本机开发时，可以从 `doubao-tts` skill 的配置文件里取地址和密钥，设成上面两个环境变量再启动 `zixuan-server`。

## 网页端选音色

网页端角色设置里的“声音”列出两部分音色：角色现在用的语音合成模型的音色，以及网页端开放的其他模型的音色，按模型分组。开放哪些模型由 `companion-web` 的 `WEB_TTS_MODEL_IDS` 决定，默认是 `TTS_EdgeTTS,TTS_DoubaoSeedTTS2`，没启用的模型会跳过。

选了另一个模型的音色并保存时，网页端把角色的语音合成模型一起换掉：读出角色现在的整组模型绑定，只把 TTS 换成选中的系统模型，其他用途原样带回。

声音旁边的“试听”按钮调用 `GET /api/v1/voices/{voiceId}/preview`：manager-api 找到音色和它的语音合成模型配置，交给 `zixuan-server` 的 `/internal/tts-preview`（按服务端密钥鉴权），用这个音色读一句固定的话。浏览器只能选音色，不能指定文本或配置，所以密钥不出服务端，也不能拿来合成任意内容。结果按音色和模型配置缓存在 manager-api 内存里（最多 64 个），同一个音色只合成一次。只支持一次返回完整音频的合成服务，双向流式的（例如 `huoshan_double_stream`）提示“暂时不能试听”。

网页对话创建会话时，管理服务把角色音色的编码放进运行时语音合成配置的 `private_voice`，和设备、调试台一致。运行时按这个音色合成；没有选音色时用模型配置里的默认音色。

## 验证

- `zixuan-server`：`tests/test_doubao_seedtts.py`，覆盖地址和密钥的来源、平台密钥不能被改道、请求内容、错误信息不含密钥。
- `manager-api`：`PublicConversationServiceTest.passesTheRoleVoiceCodeToTheRuntimeTtsConfig`。
- `companion-web`：`lib/voices.test.ts`，以及 `RoleEditor.test.tsx` 里换模型保存的用例。
- 2026-11-24 本机端到端：在网页端给角色选“豆包女声-温柔小雅”并保存，发一句话，运行时日志显示用 `zh_female_wenrouxiaoya_uranus_bigtts` 合成了回复。
