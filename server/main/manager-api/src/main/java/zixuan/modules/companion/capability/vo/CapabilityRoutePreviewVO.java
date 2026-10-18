package zixuan.modules.companion.capability.vo;

import java.util.List;

import lombok.Data;

@Data
public class CapabilityRoutePreviewVO {
    private List<String> deterministicMatches = List.of();
    private boolean semanticRequired;
    private List<String> eligibleSkillIds = List.of();
    private String selectedSkillId;
    private List<String> allowedTools = List.of();
}
