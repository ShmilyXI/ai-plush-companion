# AI-Live Runtime Protocols

## Python WebSocket

地址为 `ws://<host>:8000/xiaozhi/v1/`。客户端需要发送 `device-id`、`client-id` 和 Bearer 认证头。连接后的业务帧是设备协议 JSON/二进制消息，认证失败会关闭连接。

## MQTT

网关支持 MQTT 3.0 和 3.1.1。设备上行 topic 默认为 `device-server`，网关下行 topic 默认为 `devices/p2p/{mac}`。管理 HTTP API 的 Bearer 令牌为当天日期和 `MQTT_SIGNATURE_KEY` 拼接后 SHA-256 的十六进制结果。

设备 OTA 响应中的 `mqtt.client_id`、`mqtt.username`、`mqtt.password` 和 topic 是动态生成值，不能写死在客户端。
