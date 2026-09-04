package zixuan.modules.companion.capability.vo;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

@Data
public class SkillPackageImportVO {
    private String capabilityId;
    private String name;
    private Integer version;
    private String packageSha256;
    private Long packageSize;
    private Map<String, Object> manifest = new LinkedHashMap<>();
    private String skillMarkdown;
    private SkillPackageValidationVO validation;
}
