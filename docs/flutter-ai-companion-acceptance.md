# Flutter AI 陪伴 App 验收记录

## 范围

首期 App 位于 `app/`，只面向 Android 和 iOS。页面和运行时通过 `manager-api` 的 `/app/*`、`/companion/*` 和 `/api/v1/conversations` 接口工作；硬件仍沿用 `/companion/devices`、MQTT 和原有设备记忆接口，App 不伪装成硬件，也不接入 MQTT。

首期包含手机号或邮箱密码登录、短信或邮件验证码登录、注册、找回密码、角色新增和编辑、角色长期记忆开关、记忆查看和清理、设备绑定与控制、SoftAP 配网、持久会话、历史会话、文字聊天、按住发送语音、自动播放和全屏实时语音通话。订阅套餐购买、权益运营、Skill 编辑和高级设备运维不在首期。

## 已覆盖闭环

角色记忆使用 `companion:<userId>:<profileId>`，角色页和聊天页的开关修改同一份长期设置。关闭后运行时不召回或自动新增，已有条目仍可编辑、删除和清空。硬件入口保留设备身份请求形状，并在服务端解析到同一角色命名空间。

聊天页左侧抽屉管理 App 和设备来源会话，右侧卡片抽屉选择角色并快速切换记忆开关。文字、单轮语音和全屏通话复用同一持久会话；进入通话前会续取短期 runtime，挂断后回到原会话。流式 TTS 支持 JSON base64 和二进制帧，播放队列由 `audio_service`、`just_audio` 和 `audio_session` 共同管理。

设备向导只允许访问 `http://192.168.4.1`，精确识别主帧 `/done.html`，先完成 `/exit` 回调再进入六位激活码绑定，并在绑定后轮询设备上线。设备名称、角色、音量、亮度和解绑操作失败时不会覆盖本地有效状态。

认证 token 只保存于 Keychain/Keystore。验证码、密码、provider 凭据、MQTT 凭据、原始录音和记忆全文不写入普通日志。token 失效或退出登录时，聊天和通话 transport 会先关闭，再清理账号状态，防止旧事件写回新账号。

## 本轮验证

在 `app/` 执行 `dart format --set-exit-if-changed lib test`、`flutter analyze` 和 `flutter test`，结果分别为格式无变更、`No issues found`、127 个 Flutter 测试全部通过。新增的流式心跳回归确认 `heartbeat` 返回 `session.pong`，手动播放回归确认同一回复的全部 TTS 片段按顺序入队，角色删除回归确认当前会话状态同步切换，账号切换期间的旧 refresh 结果不会覆盖新会话，头像相对资源按 API 地址解析且不接受危险 scheme。

Android 使用 `flutter build apk --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi` 构建通过；iOS 使用 `flutter build ios --no-codesign --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi` 构建通过；Web 调试预览也构建通过。

在 `server/main/manager-api/` 使用 JDK 21 容器执行清理后的非数据库测试，904 个测试通过，失败和错误均为 0。完整 909 个测试中有 5 个旧的 `@SpringBootTest` 用例因当前环境没有本机 MySQL 而无法启动，不能记作通过。`server/main/xiaozhi-server/` 使用仓库 Python 3.12 环境全量通过 641 个测试和 31 个子测试；MQTT gateway 通过 8 个测试；固件测试通过 45 个测试和 25 个子测试；控制台通过 62 个测试文件、634 个测试，Playwright 通过 18 个浏览器测试。独立 `companion-web` 通过 1 个测试、类型检查和生产构建。两份 App/Open Conversation YAML 均通过 OpenAPI 校验，Python 编译、JavaScript 语法和差异检查通过。

当前预览地址是 `http://localhost:59029/#/chat`。预览使用演示数据，不代表真实账号、短信邮件、模型 provider 或硬件已经联调。

## 仍需现场验收

真实 Android 和 iOS 设备仍需分别核对麦克风权限、后台播放、锁屏通知、系统音频中断、WebView 回前台和 Wi-Fi 设置往返。真实后端环境仍需核对短信和邮件供应商、首次登录引导、角色版本切换、App 与设备在同一角色记忆下的读写，以及配网失败、设备离线和激活码错误提示。

真实 provider 超时或故障、动态唤醒词异常回滚、固件分区和 NVS 保护继续按 `docs/daytime-device-acceptance.md` 执行。本记录不把这些未执行项目标记为通过。
