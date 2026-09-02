# Companion Web

独立的陪伴对话 Web 应用。应用服务端只代理 manager-api 的短期 Web 会话和公共资源请求，浏览器拿到 runtime token 后直接连接 Python 公共 WebSocket，不经过 Vercel Function 中继音频长连接。

本地启动需要先启动 manager-api 和 xiaozhi-server，然后在该目录执行 `npm install` 和 `npm run dev`。默认地址为 `http://127.0.0.1:8010`，manager-api 地址由 `MANAGER_API_BASE_URL` 配置，默认是 `http://127.0.0.1:8002/xiaozhi`。管理台使用 `VITE_COMPANION_WEB_URL` 指向该地址。

部署到 Vercel 时，`MANAGER_API_BASE_URL` 必须是 Vercel 服务端可访问的 manager-api 地址；`NEXT_PUBLIC_CONSOLE_URL` 和 `NEXT_PUBLIC_CONSOLE_ORIGIN` 指向管理台公开地址。如果 manager-api 返回的 `streamUrl` 是内网主机，设置 `PUBLIC_RUNTIME_WS_ORIGIN` 为浏览器可访问的 WSS/WS 入口，Python WebSocket 仍由浏览器直接连接。

独立打开应用时，未登录会跳转到管理台的 `/playground` 完成登录，再通过一次性 code 返回。嵌入管理台时，父页面通过 `postMessage` 发送一次性 bootstrap code。manager-api 需要把独立应用 origin 加入 `companion.web.allowed-origins`。

实时连接使用 `web.session.start`、连续 PCM 二进制帧、`input.audio.commit` 和 `stream.stop`。输入音频为 16 kHz、单声道、`pcm_s16le`。文字、按次语音和持续语音共享同一个公共会话和角色版本。
