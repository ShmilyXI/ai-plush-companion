package zixuan.common.constant;

public final class ProductIdentity {
    public static final String ROUTE_PREFIX = "/zixuan";
    public static final String WEBSOCKET_ROUTE = ROUTE_PREFIX + "/v1/";
    public static final String OTA_ROUTE = ROUTE_PREFIX + "/ota/";
    public static final String PLAYGROUND_ROUTE = ROUTE_PREFIX + "/internal/playground";
    public static final String DEFAULT_WEBSOCKET_URL = "ws://zixuan.server.com:8000" + WEBSOCKET_ROUTE;
    public static final String MQTT_UPLINK_TOPIC = "zixuan/device-server";
    public static final String MQTT_DOWNLINK_PREFIX = "zixuan/devices/p2p/";

    private ProductIdentity() {
    }
}
