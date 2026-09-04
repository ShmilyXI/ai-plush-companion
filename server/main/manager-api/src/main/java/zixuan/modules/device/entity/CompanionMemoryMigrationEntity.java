package zixuan.modules.device.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_companion_memory_migration")
public class CompanionMemoryMigrationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long ownerId;
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
    private Date updatedAt;
}
