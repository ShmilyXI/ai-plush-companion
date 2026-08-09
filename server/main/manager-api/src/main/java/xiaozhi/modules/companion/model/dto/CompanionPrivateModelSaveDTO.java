package xiaozhi.modules.companion.model.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionPrivateModelSaveDTO {
    @NotBlank
    @Pattern(regexp = "LLM|ASR|TTS|VAD|VLLM|Memory")
    private String modelType;
    @NotBlank @Size(max = 64)
    private String name;
    @NotBlank @Size(max = 50)
    private String providerCode;
    @NotBlank @Size(max = 80)
    private String vendorName;
    @NotBlank @Size(max = 50)
    private String protocol;
    private Boolean credentialRequired;
    @Size(max = 32)
    private String providerTemplateId;
    @Size(max = 500)
    private String apiUrl;
    @Size(max = 4000)
    private String apiKey;
    @Size(max = 160)
    private String modelId;
    private Map<String, Object> config;
    private Map<String, Object> secrets;
    private List<@Size(max = 100) String> clearSecretKeys;
    @Min(0) @Max(1)
    private Integer enabled;
}
