package xiaozhi.modules.companion.memory;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

public interface ProfileMemoryService {
    record MemoryItem(String id, String content, @JsonAlias("updated_at") String updatedAt,
            @JsonAlias("source_device_id") String sourceDeviceId,
            @JsonAlias("source_profile_id") String sourceProfileId, String sourceDeviceName,
            String sourceProfileName) {
    }

    record MemoryView(boolean enabled, List<MemoryItem> items) {
    }

    MemoryView list(Long userId, String profileId);

    void update(Long userId, String profileId, String memoryId, String content);

    void delete(Long userId, String profileId, String memoryId);

    void clear(Long userId, String profileId);
}
