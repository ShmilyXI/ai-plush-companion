package xiaozhi.modules.companion.capability.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class PluginDefinitionDTO {
    private String executorName;
    private Map<String, Object> inputSchema;
    private Map<String, Object> configSchema;
    private List<String> secretFields;
    private Map<String, Object> defaultConfig;
}
