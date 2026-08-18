package xiaozhi.modules.companion.capability.vo;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

@Data
public class SkillPackageImportVO {
    private String capabilityId;
    private String name;
    private Integer version;
    private Map<String, Object> manifest = new LinkedHashMap<>();
    private String skillMarkdown;
    private SkillPackageValidationVO validation;
}
