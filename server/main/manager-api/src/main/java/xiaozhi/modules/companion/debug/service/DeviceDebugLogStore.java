package xiaozhi.modules.companion.debug.service;

import java.time.Duration;
import java.util.List;

import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;

public interface DeviceDebugLogStore {
    String append(String deviceId, DeviceDebugLogEvent event, long now);

    List<DeviceDebugLogEvent> history(String deviceId, int limit);

    List<DeviceDebugLogEvent> readAfter(String deviceId, String cursor, Duration block, int limit);
}
