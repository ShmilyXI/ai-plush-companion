package zixuan.modules.companion.service;

public interface AdminCompanionDeviceService {
    void rename(Long operatorId, String deviceId, String alias);

    void updateMode(Long operatorId, String deviceId, String mode);

    void unbind(Long operatorId, String deviceId);
}
