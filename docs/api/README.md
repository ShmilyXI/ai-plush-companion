# AI-Live API 文档

Apifox 项目为 `AI-Live`，项目 ID 为 `8747347`。

Java 管理后台接口来自运行中的 `manager-api` OpenAPI。Python 运行时接口由 `xiaozhi-server-openapi.json` 描述，MQTT 网关 HTTP 接口由 `mqtt-gateway-openapi.json` 描述，WebSocket 和 MQTT topic 约定见 `ai-live-runtime-protocols.md`。

源码路由发生变化后，运行下面的命令重新生成 Python 和网关 OpenAPI 文件，再在 Apifox 项目中使用 OpenAPI 导入并选择合并模式。

```bash
node scripts/generate-runtime-openapi.mjs
```

当前导入范围包含 Java 后台、Python 运行时 HTTP、MQTT 网关 HTTP，以及协议说明入口。设备 WebSocket 的业务帧仍遵循设备协议，不应当按普通 HTTP 请求调试。

公共对话接口位于 `manager-api` 的 `/api/v1` 路径。第一方 APP 使用 `Authorization: Bearer <user-token>`；第三方服务端使用 `Authorization: ApiKey <createdSecret>`。API Key 只能访问授权 scope 和 Agent 白名单内的资源，原始 Key 只在创建响应返回一次。创建会话后，客户端使用返回的短期 runtime token 连接 Python WebSocket，原始 API Key 不会进入 Python 或设备 MQTT 链路。

音频客户端发送 `turn.audio.start` 时可以提供 `duration_ms`，服务端在声明值超过 60 秒时拒绝该轮并返回 `invalid_audio`。二进制帧仍受 2 MiB 输入大小限制；未声明时长的旧客户端保持兼容，但新客户端应主动提供时长，便于服务端执行单轮资源限制。

浏览器端可以直接复用 `docs/api/public-conversation-client.js`：先用用户 Bearer token 创建会话，再用返回的短期 runtime token 作为 `Sec-WebSocket-Protocol: bearer.<runtime-token>` 建立 WebSocket。文字通过 `sendText` 发送；音频通过 `sendAudioStart`、二进制帧和 `sendAudioEnd` 发送；服务端事件和 TTS 二进制帧统一从 `onEvent` 接收。

不带 UI 的第三方客户端可以运行 `scripts/public-conversation-smoke.mjs` 做真实文字流冒烟测试。它需要 `PUBLIC_API_BASE`、`PUBLIC_AUTHORIZATION` 和 `PUBLIC_AGENT_ID`，只输出会话 ID、Agent 版本和事件类型，不输出运行令牌或 provider 配置。

本地网页参考端位于 `docs/api/public-conversation-demo.html`。在仓库根目录运行 `python3 -m http.server 8010 --directory docs/api` 后访问 `http://127.0.0.1:8010/public-conversation-demo.html`，填入第一方 Bearer token 和已发布 Agent ID 即可连接。

公共会话当前包括 `POST /api/v1/conversations`、`GET /api/v1/agents`、`GET /api/v1/models`、`GET /api/v1/voices` 和 `GET /api/v1/devices`。API Key 管理接口为 `POST/GET/DELETE /api/v1/api-keys`，仅接受第一方用户 Bearer token。
