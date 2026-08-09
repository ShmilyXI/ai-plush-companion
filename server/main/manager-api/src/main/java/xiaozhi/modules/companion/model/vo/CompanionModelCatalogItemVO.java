package xiaozhi.modules.companion.model.vo;

import java.util.List;

import lombok.Data;

@Data
public class CompanionModelCatalogItemVO {
    private String id;
    private String reference;
    private String modelType;
    private String name;
    private String providerCode;
    private String vendorCode;
    private String vendorName;
    private String protocol;
    private String providerTemplateId;
    private String apiUrl;
    private String modelId;
    private String credentialRequirement;
    private boolean credentialConfigured;
    private String credentialStatus;
    private String keyUrl;
    private String docsUrl;
    private List<String> setupGuide;
    private List<CompanionModelProviderFieldVO> credentialFields;
    private String unavailableReason;
    private String source;
    private boolean enabled;
    private boolean defaultModel;
    private long usageCount;
    private List<String> actions;
}
