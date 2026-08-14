package xiaozhi.modules.companion.wakeword.service;

import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;

public interface WakeWordGenerationClient {
    GeneratedAsset generate(DeviceWakeWordEntity row);

    record GeneratedAsset(byte[] content, String sha256, long size) {
    }
}
