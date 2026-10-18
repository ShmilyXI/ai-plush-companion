package zixuan.modules.companion.model.vo;

import lombok.Data;

@Data
public class CompanionModelOptionVO {
    private String id;
    private String modelType;
    private String name;
    private String source;
    private String providerCode;
    private boolean enabled;
    private Boolean isDefault;
    private String vendorName;
    private String protocol;
    private String credentialStatus;
    private String unavailableReason;

    public CompanionModelOptionVO(String id, String modelType, String name, String source,
            String providerCode, boolean enabled) {
        this.id = id;
        this.modelType = modelType;
        this.name = name;
        this.source = source;
        this.providerCode = providerCode;
        this.enabled = enabled;
    }
}
