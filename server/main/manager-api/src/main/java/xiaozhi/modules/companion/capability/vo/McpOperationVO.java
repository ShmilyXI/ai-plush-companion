package xiaozhi.modules.companion.capability.vo;

import java.util.List;

import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;

public record McpOperationVO(boolean success, String errorClass, List<McpToolSnapshotEntity> tools) {
}
