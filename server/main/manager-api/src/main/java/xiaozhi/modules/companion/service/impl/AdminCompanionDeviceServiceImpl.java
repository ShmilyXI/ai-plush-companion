package xiaozhi.modules.companion.service.impl;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.service.AdminCompanionDeviceService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

@Service
@AllArgsConstructor
public class AdminCompanionDeviceServiceImpl implements AdminCompanionDeviceService {
    private final DeviceService deviceService;
    private final CompanionAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long operatorId, String deviceId, String alias) {
        DeviceEntity existing = requireDevice(deviceId);
        String normalizedAlias = alias == null ? "" : alias.trim();
        if (normalizedAlias.isEmpty()) {
            throw new RenException("设备名称不能为空");
        }
        DeviceEntity update = new DeviceEntity();
        update.setId(deviceId);
        update.setAlias(normalizedAlias);
        if (!deviceService.updateById(update)) {
            throw new RenException("设备更新失败");
        }
        auditService.record(operatorId, existing.getUserId(), "device.update", "device", deviceId,
                Map.of("alias", normalizedAlias));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long operatorId, String deviceId) {
        DeviceEntity existing = requireDevice(deviceId);
        Long ownerId = existing.getUserId();
        if (ownerId == null) {
            throw new RenException("设备尚未绑定用户");
        }
        deviceService.unbindDevice(ownerId, deviceId);
        auditService.record(operatorId, ownerId, "device.unbind", "device", deviceId,
                Map.of("ownerId", ownerId));
    }

    private DeviceEntity requireDevice(String deviceId) {
        DeviceEntity device = deviceService.selectById(deviceId);
        if (device == null) {
            throw new RenException("设备不存在");
        }
        return device;
    }
}
