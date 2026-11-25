# Companion Web

独立的陪伴对话 Web 应用。应用服务端只代理 manager-api 的短期 Web 会话和公共资源请求，浏览器拿到 runtime token 后直接连接 Python 公共 WebSocket，不经过 Vercel Function 中继音频长连接。

界面的设计、页面结构和角色图片的来源见 `docs/product/companion-web-design.md`。

通话时的 Live2D 角色需要先下载 Live2D 的运行库和示例模型（不在仓库里，下载即接受 Live2D 的授权协议）：`python3 scripts/fetch-live2d.py --accept-license`。没下载时角色位置显示静态图片，其余功能不受影响。

角色还可以用 Mowa Bot 画（代码生成的 SVG 小角色，几十种动画状态）。在角色设置里点「更多小角色」可以给单个角色挑一个；设 `NEXT_PUBLIC_CHARACTER_STYLE=mowa` 则所有角色都用它，兔子、猫、小熊画成同样颜色的 Mowa Bot 造型。说话时表情跟着回复的情绪变：服务端每轮回复会发一个 `reply.emotion` 事件（`neutral`、`happy`、`gentle`、`sad`、`surprised`、`sleepy`、`concerned`），对应关系在 `lib/mowa.ts`。

Mowa Bot 是我们基于 [Aora Bot](https://github.com/sam70361/aora-bot)（MIT）改造的分支，仓库在 `ShmilyXI/mowa-bot`，没有发布到 npm。这里用的是打好的包 `vendor/mowa-bot-*.tgz`。更新时在 mowa-bot 仓库执行 `pnpm --filter @mowa-bot/engine pack --pack-destination <本目录>/vendor` 和 `pnpm --filter @mowa-bot/react pack --pack-destination <本目录>/vendor`，删掉旧版本的 tgz，改 `package.json` 里的文件名后 `npm install`。

本地启动需要先启动 manager-api 和 zixuan-server，然后在该目录执行 `npm install` 和 `npm run dev`。默认地址为 `http://127.0.0.1:8010`，manager-api 地址由 `MANAGER_API_BASE_URL` 配置，默认是 `http://127.0.0.1:8002/zixuan`。管理台使用 `VITE_COMPANION_WEB_URL` 指向该地址。

部署到 Vercel 时，`MANAGER_API_BASE_URL` 必须是 Vercel 服务端可访问的 manager-api 地址；`NEXT_PUBLIC_CONSOLE_URL` 和 `NEXT_PUBLIC_CONSOLE_ORIGIN` 指向管理台公开地址。如果 manager-api 返回的 `streamUrl` 是内网主机，设置 `PUBLIC_RUNTIME_WS_ORIGIN` 为浏览器可访问的 WSS/WS 入口，Python WebSocket 仍由浏览器直接连接。

独立打开应用时，未登录会跳转到管理台的 `/playground` 完成登录，再通过一次性 code 返回。嵌入管理台时，父页面通过 `postMessage` 发送一次性 bootstrap code。manager-api 需要把独立应用 origin 加入 `companion.web.allowed-origins`。

实时连接使用 `web.session.start`、连续 PCM 二进制帧、`input.audio.commit` 和 `stream.stop`。输入音频为 16 kHz、单声道、`pcm_s16le`。文字、按次语音和持续语音共享同一个公共会话和角色版本。
