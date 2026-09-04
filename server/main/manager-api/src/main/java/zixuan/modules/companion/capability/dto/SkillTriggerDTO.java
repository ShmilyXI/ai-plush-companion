package zixuan.modules.companion.capability.dto;

import lombok.Data;

@Data
public class SkillTriggerDTO {
    private String type;
    private String value;
    private Integer priority;
    private Boolean caseSensitive;
    private Boolean enabled;
}
