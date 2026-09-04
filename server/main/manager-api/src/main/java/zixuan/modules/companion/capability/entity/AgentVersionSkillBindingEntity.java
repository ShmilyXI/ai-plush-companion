package zixuan.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_agent_version_skill_binding")
public class AgentVersionSkillBindingEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String agentId;
    private Integer versionNo;
    private String skillId;
    private String versionMode;
    private Integer fixedVersion;
    private String overrideJson;
    private Integer triggerPriority;
    private Integer enabled;
    private String migrationSource;
    private Date createdAt;
    private Date updatedAt;
}
