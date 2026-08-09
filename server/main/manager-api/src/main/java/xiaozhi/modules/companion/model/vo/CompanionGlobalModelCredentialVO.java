package xiaozhi.modules.companion.model.vo;

import java.util.List;

import lombok.Data;

@Data
public class CompanionGlobalModelCredentialVO {
    private String globalModelId;
    private String apiUrl;
    private String modelId;
    private List<String> configuredSecretKeys;
    private String credentialRequirement;
    private boolean credentialConfigured;
    private String credentialStatus;
}
