package zixuan.modules.companion.model.vo;

import java.util.List;

import lombok.Getter;

@Getter
public final class CompanionModelMigrationAuditReportVO {
    private final int totalProfiles;
    private final int readyProfiles;
    private final int privateBindingProfiles;
    private final int missingNativeModelProfiles;
    private final int invalidTtsPairProfiles;
    private final int needsSelectionProfiles;
    private final List<String> readyAgentIds;
    private final List<String> privateBindingAgentIds;
    private final List<String> missingNativeModelAgentIds;
    private final List<String> invalidTtsPairAgentIds;
    private final List<String> needsSelectionAgentIds;

    public CompanionModelMigrationAuditReportVO(int totalProfiles,
            List<String> readyAgentIds,
            List<String> privateBindingAgentIds,
            List<String> missingNativeModelAgentIds,
            List<String> invalidTtsPairAgentIds,
            List<String> needsSelectionAgentIds) {
        this.totalProfiles = totalProfiles;
        this.readyAgentIds = List.copyOf(readyAgentIds);
        this.privateBindingAgentIds = List.copyOf(privateBindingAgentIds);
        this.missingNativeModelAgentIds = List.copyOf(missingNativeModelAgentIds);
        this.invalidTtsPairAgentIds = List.copyOf(invalidTtsPairAgentIds);
        this.needsSelectionAgentIds = List.copyOf(needsSelectionAgentIds);
        this.readyProfiles = this.readyAgentIds.size();
        this.privateBindingProfiles = this.privateBindingAgentIds.size();
        this.missingNativeModelProfiles = this.missingNativeModelAgentIds.size();
        this.invalidTtsPairProfiles = this.invalidTtsPairAgentIds.size();
        this.needsSelectionProfiles = this.needsSelectionAgentIds.size();
    }
}
