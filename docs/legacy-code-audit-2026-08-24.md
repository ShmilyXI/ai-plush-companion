# 历史代码与前端残留审计

本文记录 2026-08-24 对 Java、Python、网关和前端目录的静态清理结果。清理依据是 Git 追踪状态、Docker/Compose 构建入口、源码引用和现有测试，不按目录名称猜测用途。

旧版 `server/main/manager-web` 的源码已经在 `ea420ec` 中删除，工作区残留的构建产物、依赖、旧环境文件和生成器资源已移到 `/tmp/legacy-manager-web-20260824`。当前 Docker、Compose、部署文档和管理 API 测试只引用 `companion-console`，唤醒词资源来自 `xiaozhi-server/models/wake_word`。项目内只保留历史名称的文档说明和防回归断言。

`server/main/manager-mobile` 仍是被 Git 追踪的 UniApp 工程，包含登录、角色、设备、语音设置和管理移动端页面；它不是旧桌面管理台的副本，不能删除。`server/main/digital-human` 仍被服务端测试文档、音频交互测试入口、唤醒词运行时和一体机部署文档引用，属于独立的虚拟设备测试工具，不能从运行链路审计中误删。

`server/main/shared/wake_word_assets` 被 Python 唤醒词生成器、Dockerfile 和固件资产测试共同引用。新增的 `server/__init__.py`、`server/main/__init__.py` 与 Python 测试 `conftest.py` 只负责让主机测试使用和 Docker 相同的共享包路径，不承载业务逻辑。

`server/main/java` 当前没有 Git 追踪文件，也没有 Java 构建入口或源码引用，属于空的历史目录；`manager-api` 的 Java 源码和资源均由 `pom.xml`、Spring 扫描和 MyBatis 配置进入构建，不能按模块名批量删除。Python 的 `performance_tester`、`tests`、`digital-human` 和 provider 目录都存在文档或测试入口，暂不列为死代码。

本轮门禁结果为 Python 全量 `491 passed`、公共会话 `39 passed`、动态唤醒词静态测试 `8 passed`、manager-api conversation 测试通过、控制台已有 `630` 个 Vitest 测试通过。统一验收脚本还通过了控制台 `16` 个 Playwright E2E、Python 能力/迁移 `74 passed`、网关 `8 passed`、模拟 rollout 和 OpenSpec 校验。脚本已能自动选择仓库内 JDK21，避免使用 JDK17 运行 JDK21 编译产物。下一次历史代码删除必须先补充生产引用、数据库兼容和回滚证据，再单独提交删除变更。
