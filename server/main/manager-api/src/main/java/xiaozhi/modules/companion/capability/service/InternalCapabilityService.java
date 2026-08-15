package xiaozhi.modules.companion.capability.service;

import xiaozhi.modules.companion.capability.dto.DeviceToolSnapshotSaveDTO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO;

public interface InternalCapabilityService {
    EffectiveCapabilityBundleVO bundle(String deviceId);

    void saveDeviceTools(String deviceId, DeviceToolSnapshotSaveDTO request);

    String secret(String deviceId, String secretId);
}
