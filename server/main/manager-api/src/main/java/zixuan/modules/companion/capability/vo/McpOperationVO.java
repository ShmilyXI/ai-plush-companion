package zixuan.modules.companion.capability.vo;

import java.util.List;

import zixuan.modules.companion.capability.entity.McpToolSnapshotEntity;

public record McpOperationVO(boolean success, String errorClass, List<McpToolSnapshotEntity> tools) {
}
