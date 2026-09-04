package zixuan.modules.companion.capability.service;

import zixuan.modules.companion.capability.dto.DeviceToolSnapshotSaveDTO;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO;

public interface InternalCapabilityService {
    EffectiveCapabilityBundleVO bundle(String deviceId);

    void saveDeviceTools(String deviceId, DeviceToolSnapshotSaveDTO request);

    String secret(String deviceId, String secretId);
}
