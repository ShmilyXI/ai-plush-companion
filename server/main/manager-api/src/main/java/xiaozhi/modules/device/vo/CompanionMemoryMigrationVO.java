package xiaozhi.modules.device.vo;

import java.util.Date;

import lombok.Data;
import xiaozhi.modules.device.entity.CompanionMemoryMigrationEntity;

@Data
public class CompanionMemoryMigrationVO {
    private String id;
    private String agentId;
    private String sourceDeviceId;
    private String targetDeviceId;
    private String mode;
    private Integer sourceCount;
    private Integer targetCount;
    private Integer importedCount;
    private Integer skippedCount;
    private String outcome;
    private Boolean retryable;
    private Boolean recovered;
    private Long operatorId;
    private Date createdAt;

    public static CompanionMemoryMigrationVO from(CompanionMemoryMigrationEntity entity) {
        CompanionMemoryMigrationVO vo = new CompanionMemoryMigrationVO();
        vo.id = entity.getId();
        vo.agentId = entity.getAgentId();
        vo.sourceDeviceId = entity.getSourceDeviceId();
        vo.targetDeviceId = entity.getTargetDeviceId();
        vo.mode = entity.getMode();
        vo.sourceCount = entity.getSourceCount();
        vo.targetCount = entity.getTargetCount();
        vo.importedCount = entity.getImportedCount();
        vo.skippedCount = entity.getSkippedCount();
        vo.outcome = entity.getOutcome();
        vo.retryable = entity.getRetryable();
        vo.recovered = entity.getRecovered();
        vo.operatorId = entity.getOperatorId();
        vo.createdAt = entity.getCreatedAt();
        return vo;
    }
}
