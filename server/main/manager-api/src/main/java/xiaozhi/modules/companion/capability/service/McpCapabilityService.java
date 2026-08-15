package xiaozhi.modules.companion.capability.service;

import java.util.List;

import xiaozhi.modules.companion.capability.dto.McpSyncDTO;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;

public interface McpCapabilityService {
    List<McpToolSnapshotEntity> sync(String serverId, McpSyncDTO request);

    List<McpToolSnapshotEntity> list(String capabilityId);

    List<McpToolSnapshotEntity> approve(Long operatorId, String capabilityId, List<String> approvedToolIds);
}
