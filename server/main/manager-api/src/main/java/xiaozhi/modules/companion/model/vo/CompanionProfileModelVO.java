package xiaozhi.modules.companion.model.vo;

import java.util.Map;

import lombok.Data;

@Data
public class CompanionProfileModelVO {
    private String modelType;
    private String source;
    private String resourceId;
    private String name;
    private Map<String, Object> overrides;
    private boolean enabled;
    private String unavailableReason;
}
