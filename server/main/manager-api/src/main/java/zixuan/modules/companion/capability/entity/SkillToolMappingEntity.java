package zixuan.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_skill_tool_mapping")
public class SkillToolMappingEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String skillId;
    private String toolType;
    private String toolRefId;
    private String toolName;
    private String toolAlias;
    private String purpose;
    private String defaultParamsJson;
    private Integer required;
    private Integer sortOrder;
    private Date createdAt;
    private Date updatedAt;
}
