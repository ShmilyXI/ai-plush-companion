# 前端与运行时资产盘点

当前管理主界面是 `server/main/companion-console`，技术栈为 React、TypeScript 和 Vite。它承载角色、模型、音色、设备、记忆、能力和管理审计等新流程，README、构建脚本和测试均以它为主。

`server/main/companion-web` 是独立的 Next.js 普通用户对话应用。它使用一次性 Web SSO 复用管理台当前用户，提供角色和音色选择、文字对话、按次语音和持续语音；管理台 `/playground` 只保留 iframe 容器，便于独立地址直接打开。

`server/main/manager-mobile` 是独立的移动端/小程序工程，使用 UniApp 和 Vue 3。它面向移动端管理，不是桌面管理台的旧副本。后续普通用户 APP 应复用公共对话 API，而不是把管理员页面复制到移动端。

`server/main/manager-web` 是旧版 Vue 2 管理台。它不再是当前管理界面，也不应继续作为服务端构建依赖。它原来唯一被服务端镜像引用的唤醒词 Multinet 资源已经迁移到 `server/main/zixuan-server/models/wake_word`，Docker 镜像现在只从 Python runtime 目录复制代码和模型。

旧版目录已移除。Docker、Compose、部署文档和管理 API 测试已经切换到 `companion-console` 或 Python runtime 自有资源，静态引用检查只保留本盘点文档和防回归断言中的历史名称。
