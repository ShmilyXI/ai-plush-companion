package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_skill_trigger")
public class SkillTriggerEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String skillId;
    private String triggerType;
    private String patternText;
    private Integer priority;
    private Integer caseSensitive;
    private Integer enabled;
    private Date createdAt;
    private Date updatedAt;
}
