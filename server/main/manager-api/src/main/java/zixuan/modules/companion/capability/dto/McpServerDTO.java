package zixuan.modules.companion.capability.dto;

import java.util.Date;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class McpServerDTO {
    private String transport;
    private Map<String, Object> connectionConfig;
    private Map<String, String> secretRefs;
    private Map<String, Object> approvedCommandTemplate;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String healthStatus;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String lastError;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Date lastCheckedAt;
}
