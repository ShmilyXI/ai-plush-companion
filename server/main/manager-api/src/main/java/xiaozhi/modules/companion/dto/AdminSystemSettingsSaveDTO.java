package xiaozhi.modules.companion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminSystemSettingsSaveDTO {
    @NotBlank
    private String publicWebsocketUrl;
    @NotBlank
    private String publicOtaUrl;
    @NotBlank
    private String xiaozhiListenHost;
    @NotNull
    @Min(1)
    @Max(65535)
    private Integer xiaozhiListenPort;
    @NotBlank
    private String otaListenHost;
    @NotNull
    @Min(1)
    @Max(65535)
    private Integer otaListenPort;
    @NotBlank
    private String defaultLlmModelId;
    private String defaultVllmModelId;
    @NotBlank
    private String defaultTtsModelId;
    @NotBlank
    private String defaultAsrModelId;
    @NotBlank
    private String defaultVadModelId;
    private String defaultMemoryModelId;
    @NotBlank
    private String defaultTtsVoiceId;
}
