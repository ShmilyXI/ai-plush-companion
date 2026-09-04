PRODUCT_ID = "zixuan"
PRODUCT_ROUTE_PREFIX = "/zixuan"
WEBSOCKET_ROUTE = f"{PRODUCT_ROUTE_PREFIX}/v1/"
OTA_ROUTE = f"{PRODUCT_ROUTE_PREFIX}/ota/"
OTA_DOWNLOAD_ROUTE = f"{OTA_ROUTE}download/{{filename}}"
PLAYGROUND_ROUTE = f"{PRODUCT_ROUTE_PREFIX}/internal/playground"
MQTT_UPLINK_TOPIC = "zixuan/device-server"
MQTT_DOWNLINK_PREFIX = "zixuan/devices/p2p/"


def device_downlink_topic(device_id_safe: str) -> str:
    return f"{MQTT_DOWNLINK_PREFIX}{device_id_safe}"
