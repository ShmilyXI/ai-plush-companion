# 拾光陪伴 Flutter App

这是面向普通用户的 Android/iOS Flutter 客户端。首期包含账号认证、角色、设备、记忆、持久会话、文字聊天、单轮语音、实时语音通话和 SoftAP 配网。订阅购买与权益页面暂不进入客户端。

## 本地预览

在 `app/` 执行 `flutter run -d web-server --web-port 59027`。不提供 `API_BASE_URL` 时使用演示数据，适合检查页面和交互。

## 连接后端

使用 `flutter run --dart-define=API_BASE_URL=https://your-manager-api.example/xiaozhi`，或在构建时传入同名参数。发布构建要求 HTTPS。实时会话地址由服务端 runtime 响应提供，也可以用 `RUNTIME_WS_ORIGIN` 覆盖 WebSocket origin。

## 构建

Android 使用 `flutter build apk --debug --dart-define=API_BASE_URL=https://your-manager-api.example/xiaozhi`。iOS 使用 `flutter build ios --no-codesign --debug --dart-define=API_BASE_URL=https://your-manager-api.example/xiaozhi`。正式发布前需要配置各自的签名、推送和商店元数据。

## 后端边界

App 使用 `/app/auth`、`/app/account`、`/app/profiles`、`/companion/devices`、`/companion/profiles/{id}/memories` 和 `/api/v1/conversations`。硬件继续使用原有设备 MQTT 和设备记忆接口，App 不接入 MQTT。设备配网是唯一访问 `http://192.168.4.1` 的本地链路。
