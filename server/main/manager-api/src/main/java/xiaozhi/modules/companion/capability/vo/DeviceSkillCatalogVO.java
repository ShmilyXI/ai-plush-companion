package xiaozhi.modules.companion.capability.vo;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class DeviceSkillCatalogVO {
    private String skillId;
    private String name;
    private String description;
    private Integer publishedVersion;
    private Integer packageVersion;
    private String packageSha256;
    private String packageSource;
    private List<Integer> versions = List.of();
    private List<String> overridableFields = List.of();
    private Map<String, Object> defaults = Map.of();
    private boolean available;
    private String unavailableReason;
}
