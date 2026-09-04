const PRODUCT_ID = 'zixuan';
const MQTT_UPLINK_TOPIC = `${PRODUCT_ID}/device-server`;
const MQTT_DOWNLINK_PREFIX = `${PRODUCT_ID}/devices/p2p/`;

function deviceDownlinkTopic(deviceIdSafe) {
    return `${MQTT_DOWNLINK_PREFIX}${deviceIdSafe}`;
}

function isDeviceUplinkTopic(topic) {
    return topic === MQTT_UPLINK_TOPIC;
}

function isDeviceDownlinkTopic(topic, deviceIdSafe) {
    return topic === deviceDownlinkTopic(deviceIdSafe);
}

module.exports = {
    PRODUCT_ID,
    MQTT_UPLINK_TOPIC,
    MQTT_DOWNLINK_PREFIX,
    deviceDownlinkTopic,
    isDeviceUplinkTopic,
    isDeviceDownlinkTopic
};
