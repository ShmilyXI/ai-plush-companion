package xiaozhi.modules.companion.vo;

import lombok.Data;

@Data
public class CompanionSkillBindingVO {
    private String skillId;
    private String versionMode;
    private Integer fixedVersion;
    private String overrideJson;
    private Integer triggerPriority;
    private Boolean enabled;
}
