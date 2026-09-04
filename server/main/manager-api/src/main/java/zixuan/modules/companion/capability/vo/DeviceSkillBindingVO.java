package zixuan.modules.companion.capability.vo;

import java.util.Map;

import lombok.Data;

@Data
public class DeviceSkillBindingVO {
    private String skillId;
    private String skillName;
    private String versionMode;
    private Integer fixedVersion;
    private Integer resolvedVersion;
    private boolean enabled;
    private Map<String, Object> overrides = Map.of();
    private Integer triggerPriority;
    private Long configVersion;
}
