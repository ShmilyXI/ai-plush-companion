package xiaozhi.modules.companion.model.vo;

import java.util.List;

import lombok.Data;

@Data
public class CompanionModelPresetVO {
    private String globalModelId;
    private String vendorCode;
    private String vendorName;
    private String protocol;
    private String defaultApiUrl;
    private String credentialRequirement;
    private List<CompanionModelProviderFieldVO> credentialFields;
    private String keyUrl;
    private String docsUrl;
    private List<String> setupGuide;
}
