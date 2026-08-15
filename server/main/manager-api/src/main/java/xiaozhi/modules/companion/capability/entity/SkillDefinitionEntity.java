package xiaozhi.modules.companion.capability.entity;

import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_skill_definition")
public class SkillDefinitionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityId;
    private String executionPrompt;
    private String triggerMode;
    private String ruleMode;
    private BigDecimal semanticThreshold;
    private String responseMode;
    private Integer timeoutMs;
    private String failureMessage;
    private String overridableFieldsJson;
    private Date createdAt;
    private Date updatedAt;
}
