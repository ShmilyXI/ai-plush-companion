package zixuan.modules.companion.capability.dto;

import java.util.Map;

import lombok.Data;

@Data
public class SkillToolDTO {
    private String toolType;
    private String toolRefId;
    private String toolName;
    private String alias;
    private String purpose;
    private Map<String, Object> defaultParams;
    private Boolean required;
    private Integer sortOrder;
}
