package zixuan.modules.companion.capability.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import lombok.Data;
import zixuan.modules.companion.capability.dto.McpServerDTO;
import zixuan.modules.companion.capability.dto.PluginDefinitionDTO;
import zixuan.modules.companion.capability.dto.SkillToolDTO;
import zixuan.modules.companion.capability.dto.SkillTriggerDTO;

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
    private Object deviceRequirements;
    private Integer packageVersion;
    private String packageSha256;
    private String packageSource;
    private String packageValidationStatus;
    private List<SkillPackageVO.ValidationIssueVO> packageValidationIssues = List.of();
    private List<SkillTriggerDTO> triggers = List.of();
    private List<SkillToolDTO> tools = List.of();
    private PluginDefinitionDTO plugin;
    private McpServerDTO mcp;
    private Date createdAt;
    private Date updatedAt;
}
