package xiaozhi.modules.companion.capability.dto;

import java.util.Map;

import lombok.Data;

@Data
public class McpServerDTO {
    private String transport;
    private Map<String, Object> connectionConfig;
    private Map<String, String> secretRefs;
    private Map<String, Object> approvedCommandTemplate;
}
