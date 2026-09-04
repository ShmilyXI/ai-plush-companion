# 紫萱产品改名发布证据

## 发布身份

本次切换使用 `zixuan-cutover-v1` 合同和固件版本 `2.2.7`。服务端发布清单位于 `deploy/zixuan/release-manifest.json`，固件发布清单位于 `firmware/releases/zixuan-release-manifest.json`。机器身份统一为 `zixuan`，用户显示身份统一为紫萱。上游仓库、`xiaozhi-fonts`、Espressif 唤醒模型符号和已执行 Liquibase 历史按允许清单保留原名。

## 构建与门禁

Java 全量 867 项、Python 全量 556 项和 31 个子测试、控制台 636 项与 18 项 Playwright、companion-web 测试和构建均已通过。固件回归为 45 项和 25 个子测试，`zhengchen-cam` 与 `bread-compact-wifi-s3cam` 均使用各自 `config.json` 完成 ESP-IDF 5.5.2 构建。品牌扫描结果为 0 个未分类命中，所有保留项均属于上游、厂商、历史或迁移类别。

Python 发布镜像 `ai-plush/zixuan-server:2.2.7` 的内容 ID 为 `863625a2f89c76980085c447443620bb4b8711df0ac9406102a96fbe84d43cd0`。镜像内不存在本机 `.config.yaml`、唤醒词状态、Memory 锁文件或 `.env` 密钥文件。MQTT 发布包只包含运行代码、示例配置和依赖清单，不包含本地配置、日志、测试或缓存。

## 固件产物

`zhengchen-cam` 应用 SHA-256 为 `a91ffae976db3b2632f4993aa8fabfff45c621a4e63b02913ee1465aae34d061`，assets SHA-256 为 `8c04bca392737028512097f341f1609c29e24652e38db9e8caadf386177c8fa4`。`bread-compact-wifi-s3cam` 应用 SHA-256 为 `860886adb08389154633ffcd9f456b7f63cfc5d4d98c79e7b67203c405ece657`，assets SHA-256 为 `56335b7ee25ffd6097540afcec65866fbe668e3c3fdac5df3af49e230b501aa7`。

两套 assets 均为 8 MiB，槽 A 使用 `XZWK` 布局 2 和 3 MiB 槽位，包含 `ni hao zi xuan` 与你好紫萱，槽 B 保持擦除。发布刷写计划只覆盖 `0x0`、`0x8000`、`0xd000`、`0x20000` 和 `0x800000`，不覆盖位于 `0x9000` 的 NVS，也不使用 merged binary。

## 数据切换

停机快照保存在本机 `.codex-tmp/zixuan-cutover-20260905/snapshots`，共 36 个文件并带有 SHA-256 清单。MySQL 从 `xiaozhi_esp32_server` 迁移到 `zixuan_esp32_server`，两侧均为 59 张表，Liquibase 历史校验通过。Redis 从冻结的源命名空间复制 4 个仍有效的业务键到 `zixuan:`，没有冲突。Python 运行数据的源、目标和快照摘要均为 `02041a3ed7bbf3b2752f78b2267093ba9a7ca4fc5bafe96c6ce1a4a1988da996`。

新 schema 中的 `server.name`、前端地址和协议模板键已迁移到紫萱身份，运行配置不再包含旧产品路由。旧 schema、Redis RDB、Memory Core volume、旧 Java jar、旧 MQTT 源码包、旧 Python 镜像和旧固件均保留用于整体回滚。

## 服务验收

本机当前运行 `zixuan-manager-api.jar`、`ai-plush/zixuan-server:2.2.7`、Zixuan MQTT gateway、companion-web、Memory Core、MySQL、Redis 和 Nginx 代理。控制台、manager-api、Python HTTP、Memory Core 和 companion-web 均返回成功响应，MQTT 1883 可连接。代理对 `/xiaozhi` 返回 410，Java 旧上下文返回 404，新 `/zixuan` 接口返回 200。

真实浏览器已验证紫萱管理台标题、管理员认证链路和能力中心。能力中心成功加载 6 条 Skill 与 Plugin 数据，浏览器控制台为 0 个错误。companion-web 不再跳转到已移除的 `/playground`，未授权时停留在原页面并显示网页对话暂未开放。

## 真实设备状态

已连接设备识别为 ESP32-S3、16 MiB Flash，MAC 为 `7c:0c:5f:40:49:54`，数据库板型为 `zhengchen-cam`，版本为 `2.2.7`，屏幕和摄像头能力均存在。切换前 NVS 已只读备份为 16 KiB，SHA-256 为 `25cc9f3f05973affe8bd4aa0af5fbd6ac94bfc52efcf21709adfbae32529b6a5`。

设备尚未进入 ROM 下载模式。default reset、USB reset、no-reset、`cu`、`tty` 和加长 DTR/RTS 时序均在任何写入前失败，因此应用与 assets 尚未刷入，任务 10.4 和 10.5 保持未完成。下一步需要在按住 BOOT 的同时复位设备，确认 esptool 能读取 MAC 后再执行五分区刷写、NVS 前后比较和完整真实设备验收。
