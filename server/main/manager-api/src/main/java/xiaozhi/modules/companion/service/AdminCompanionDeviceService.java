package xiaozhi.modules.companion.service;

public interface AdminCompanionDeviceService {
    void rename(Long operatorId, String deviceId, String alias);

    void unbind(Long operatorId, String deviceId);
}
