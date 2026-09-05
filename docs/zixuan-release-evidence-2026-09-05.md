# 紫萱产品改名发布证据

## 发布身份

本次切换使用 `zixuan-cutover-v1` 合同和固件版本 `2.2.7`。服务端发布清单位于 `deploy/zixuan/release-manifest.json`，固件发布清单位于 `firmware/releases/zixuan-release-manifest.json`。机器身份统一为 `zixuan`，用户显示身份统一为紫萱。上游仓库、`xiaozhi-fonts`、Espressif 唤醒模型符号和已执行 Liquibase 历史按允许清单保留原名。

## 构建与门禁

Java 全量 867 项、Python 全量 556 项和 31 个子测试、控制台 636 项与 18 项 Playwright、companion-web 6 项测试和构建均已通过。固件与 gateway 回归分别为 59 项加 25 个子测试、17 项；`zhengchen-cam` 与 `bread-compact-wifi-s3cam` 均使用各自 `config.json` 完成 ESP-IDF 5.5.2 构建。品牌扫描结果为 0 个未分类命中，1658 个保留命中均由上游、厂商、历史或已执行迁移白名单分类。

Python 发布镜像 `ai-plush/zixuan-server:2.2.7` 的内容 ID 为 `863625a2f89c76980085c447443620bb4b8711df0ac9406102a96fbe84d43cd0`。镜像内不存在本机 `.config.yaml`、唤醒词状态、Memory 锁文件或 `.env` 密钥文件。MQTT 发布包只包含运行代码、示例配置和依赖清单，不包含本地配置、日志、测试或缓存。

## 固件产物

`zhengchen-cam` 应用 SHA-256 为 `11e68cc4f98b8a0076169763ba88f2afb7df1846ba9b7011aa31b1bcdc60aa78`，assets SHA-256 为 `8c04bca392737028512097f341f1609c29e24652e38db9e8caadf386177c8fa4`。`bread-compact-wifi-s3cam` 应用 SHA-256 为 `6317a5bd9b096f3fbfcf6b47574de2383bbc9e50bf039e4ec446650e77c049dc`，assets SHA-256 为 `56335b7ee25ffd6097540afcec65866fbe668e3c3fdac5df3af49e230b501aa7`。两套固件均绑定源码 `8ad0e17d759a95f6b8fceac3e0b0615ce237e079`，包含旧 MQTT topic 拒绝逻辑。

两套 assets 均为 8 MiB，槽 A 使用 `XZWK` 布局 2 和 3 MiB 槽位，包含 `ni hao zi xuan` 与你好紫萱，槽 B 保持擦除。发布刷写计划只覆盖 `0x0`、`0x8000`、`0xd000`、`0x20000` 和 `0x800000`，不覆盖位于 `0x9000` 的 NVS，也不使用 merged binary。

## 数据切换

停机快照保存在本机 `.codex-tmp/zixuan-cutover-20260905/snapshots`，共 36 个文件并带有 SHA-256 清单。MySQL 从 `xiaozhi_esp32_server` 迁移到 `zixuan_esp32_server`，两侧均为 59 张表，Liquibase 历史校验通过。冻结旧库又被复制到临时验证库，17 组用户、设备、Agent、能力、会话、Memory 与审计归属指标全部一致，报告状态为 `ready`，临时库随后删除。Redis 从冻结的源命名空间复制 4 个仍有效的业务键到 `zixuan:`，没有冲突。Python 运行数据的源、目标和快照摘要均为 `02041a3ed7bbf3b2752f78b2267093ba9a7ca4fc5bafe96c6ce1a4a1988da996`。

新 schema 中的 `server.name`、前端地址和协议模板键已迁移到紫萱身份，运行配置不再包含旧产品路由。旧 schema、Redis RDB、Memory Core volume、旧 Java jar、旧 MQTT 源码包、旧 Python 镜像和旧固件均保留用于整体回滚。

## 服务验收

本机当前运行 `zixuan-manager-api.jar`、`ai-plush/zixuan-server:2.2.7`、Zixuan MQTT gateway、companion-web、Memory Core、MySQL、Redis 和 Nginx 代理。Python 模型挂载已移到 `Application Support/Zixuan/models`，运行容器不再读取旧服务目录。控制台、manager-api、Python HTTP、Memory Core 和 companion-web 均返回成功响应，MQTT 1883 可连接。代理对 `/xiaozhi` 返回 410，Java 旧上下文返回 404，新 `/zixuan` 接口返回 200。

gateway 在设备工具初始化时同步视觉 URL 和按设备生成的一小时 token，普通日志只记录 `[redacted]`。真实设备摄像头调用返回 200，并完成拍照、上传、鉴权和视觉解释。发布包中的 MQTT gateway SHA-256 为 `341ea9a8284721cdf0704d16faa5d7c8295852317df74dba998fece63ecdb717`，控制台 SHA-256 为 `e541b46480a76e19346e1933aa43f045725ac84e2a6584e90120db8d532c3255`。完整安装和回滚演练状态为 `passed`。

真实浏览器已验证紫萱管理台标题、管理员认证链路和能力中心。能力中心成功加载 6 条 Skill 与 Plugin 数据，浏览器控制台为 0 个错误。companion-web 不再跳转到已移除的 `/playground`，未授权时停留在原页面并显示网页对话暂未开放。

## 真实设备状态

已连接设备识别为 ESP32-S3、16 MiB Flash，MAC 为 `7c:0c:5f:40:49:54`，数据库板型为 `zhengchen-cam`，版本为 `2.2.7`，屏幕和摄像头能力均存在。最终刷写前 NVS 已只读备份为 16 KiB，SHA-256 为 `dd6b43d2e1adbede7dbbef284dcb1ff3950023bfe5a6fdd8193cc0dad00b727a`。刷写只覆盖五个批准分区，写入校验通过；刷后 NVS SHA-256 为 `b78a639985cc0c9841020101b1d690e9ddef236643fa8e628c47e45c1a75f080`。

NVS 前后均为 26 个键，没有缺失或新增。UUID、Wi-Fi、MQTT 身份、唤醒词、音量、亮度和主题保持不变；只有 MQTT 上下行 topic、WebSocket URL 和会话 token 更新到紫萱合同。设备上报布局 2、8 MiB assets、3 MiB 槽位、你好紫萱、8 个可用设备工具和 3 个已绑定 Skill。

真机在会话 `4625c076-9474-4ad0-b1f8-4a895b3c66fd` 中识别你好紫萱和时间问题，完成 ASR、DeepSeek LLM 与双段 TTS 下发。随后在长故事播放中识别停止，服务端收到 abort 并停止当前 TTS。屏幕亮度和主题完成 30/浅色到 40/深色再恢复的往返，gateway 重启和设备主动重启后均自动回到 `zixuan/device-server`，OTA 新入口返回正常，旧入口分别由应用 404 和代理 410 拒绝。

产品负责人确认 `bread-compact-wifi-s3cam` 是另一块当前不再使用的硬件，不属于本次物理切换清单。该板仍保留独立构建产物、assets 和静态回归；本次真机验收以唯一在用的 `zhengchen-cam` 为准，任务 10.4 和 10.5 完成。

## 架构证据

架构 JSON 已从紫萱源码树重新生成，来源 revision 为 `31a84d8ee8b98b00a39dee96bcae0bd6ac861fb6`，11 个源码引用全部校验通过。showcase 校验为 9/9，错误和警告均为 0；1440×900、1600×1000、1920×1080 与 2048×1320 均无溢出，明暗主题截图已人工检查。JSON SHA-256 为 `4a822b5c13f0ef52175ccebc13d717aa4e9a17e1c5cfe1f50991752d564dc2e3`，HTML SHA-256 为 `c3c7fa13ec6fed516674c406470299f29dafb9ce27559202c4912405d637d21d`。
