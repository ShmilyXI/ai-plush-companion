package xiaozhi.modules.device.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface CompanionMemoryService {
    record MemoryItem(String id, String content, @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("source_device_id") String sourceDeviceId,
            @JsonProperty("source_profile_id") String sourceProfileId,
            String sourceDeviceName, String sourceProfileName) {
    }

    List<MemoryItem> list(Long operatorId, Long ownerId, String deviceId);

    void update(Long operatorId, Long ownerId, String deviceId, String memoryId, String content);

    void delete(Long operatorId, Long ownerId, String deviceId, String memoryId);

    void clear(Long operatorId, Long ownerId, String deviceId);
}
