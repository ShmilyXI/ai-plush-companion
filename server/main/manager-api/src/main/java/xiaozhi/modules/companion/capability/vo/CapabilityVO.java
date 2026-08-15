package xiaozhi.modules.companion.capability.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import lombok.Data;
import xiaozhi.modules.companion.capability.dto.McpServerDTO;
import xiaozhi.modules.companion.capability.dto.PluginDefinitionDTO;
import xiaozhi.modules.companion.capability.dto.SkillToolDTO;
import xiaozhi.modules.companion.capability.dto.SkillTriggerDTO;

@Data
public class CapabilityVO {
    private String id;
    private String type;
    private String name;
    private String description;
    private String status;
    private Integer draftVersion;
    private Integer publishedVersion;
    private String executionPrompt;
    private BigDecimal semanticThreshold;
    private String responseMode;
    private Integer timeoutMs;
    private String failureMessage;
    private List<SkillTriggerDTO> triggers = List.of();
    private List<SkillToolDTO> tools = List.of();
    private PluginDefinitionDTO plugin;
    private McpServerDTO mcp;
    private Date createdAt;
    private Date updatedAt;
}
