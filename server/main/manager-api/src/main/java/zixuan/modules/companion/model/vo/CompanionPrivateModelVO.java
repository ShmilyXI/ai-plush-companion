package zixuan.modules.companion.model.vo;

import java.util.Map;
import java.util.Set;

import lombok.Data;

@Data
public class CompanionPrivateModelVO {
    private String id;
    private String modelType;
    private String name;
    private String providerCode;
    private String vendorName;
    private String protocol;
    private boolean credentialRequired;
    private String providerTemplateId;
    private String source;
    private String apiUrl;
    private String modelId;
    private Map<String, Object> config;
    private boolean apiKeyConfigured;
    private Set<String> configuredSecretKeys;
    private boolean enabled;
    private long usageCount;
}
