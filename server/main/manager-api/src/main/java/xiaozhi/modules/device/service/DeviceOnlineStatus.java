package xiaozhi.modules.device.service;

import java.util.Date;

public final class DeviceOnlineStatus {
    private static final long ONLINE_WINDOW_MILLIS = 150_000L;

    private DeviceOnlineStatus() {
    }

    public static boolean isOnline(Date lastConnectedAt) {
        return lastConnectedAt != null
                && System.currentTimeMillis() - lastConnectedAt.getTime() <= ONLINE_WINDOW_MILLIS;
    }
}
