package zixuan.modules.companion.model.vo;

import java.util.Map;

import lombok.Data;

@Data
public class CompanionEffectiveModelVO {
    private String modelType;
    private String resourceId;
    private String name;
    private String source;
    private String modelId;
    private boolean overridden;
    private Map<String, Object> overrides;
    private boolean enabled;
    private String unavailableReason;
}
