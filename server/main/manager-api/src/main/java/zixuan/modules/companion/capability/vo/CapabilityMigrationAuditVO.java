package zixuan.modules.companion.capability.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

@Data
public class CapabilityMigrationAuditVO {
    private long totalAgents;
    private long agentsMissingInitialVersion;
    private long enabledLegacyBindings;
    private long projectedLegacyBindings;
    private long conflictCount;
    private long retryableFailureCount;
    private long skippedRows;
    private int unmappedCount;
    private List<UnmappedLegacyRowVO> unmapped = List.of();

    @Data
    public static class UnmappedLegacyRowVO {
        private String legacyMappingId;
        private String summary;
        private Date recordedAt;
    }
}
