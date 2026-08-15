package xiaozhi.modules.companion.capability.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
public class CapabilitySaveDTO {
    private String type;
    private String name;
    private String description;
    private String executionPrompt;
    private BigDecimal semanticThreshold;
    private String responseMode;
    private Integer timeoutMs;
    private String failureMessage;
    private List<SkillTriggerDTO> triggers;
    private List<SkillToolDTO> tools;
    private PluginDefinitionDTO plugin;
    private McpServerDTO mcp;
    @JsonIgnore
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknown(String name, Object value) {
        unknownFields.put(name, value);
    }
}
