package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_skill_mapping")
public class DeviceSkillMappingEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String deviceId;
    private String skillId;
    private String versionMode;
    private Integer fixedVersion;
    private Integer enabled;
    private String overrideJson;
    private Integer triggerPriority;
    private Long configVersion;
    private Date createdAt;
    private Date updatedAt;
}
