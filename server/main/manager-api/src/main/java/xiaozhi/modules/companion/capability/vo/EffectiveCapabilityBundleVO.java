package xiaozhi.modules.companion.capability.vo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class EffectiveCapabilityBundleVO {
    private String deviceId;
    private Long configVersion;
    private List<EffectiveSkillVO> skills = List.of();
    private Map<String, EffectiveToolVO> tools = new LinkedHashMap<>();

    @Data
    public static class EffectiveSkillVO {
        private String id;
        private Integer version;
        private String name;
        private String description;
        private String executionPrompt;
        private BigDecimal semanticThreshold;
        private String responseMode;
        private Integer timeoutMs;
        private String failureMessage;
        private Integer bindingPriority;
        private List<Map<String, Object>> triggers = List.of();
        private List<String> toolNames = List.of();
        private Map<String, Object> defaults = Map.of();
    }

    @Data
    public static class EffectiveToolVO {
        private String name;
        private String type;
        private String refId;
        private String alias;
        private String purpose;
        private boolean required;
        private Map<String, Object> defaults = Map.of();
    }
}
