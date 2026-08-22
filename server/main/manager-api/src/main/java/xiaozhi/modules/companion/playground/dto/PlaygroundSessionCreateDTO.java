package xiaozhi.modules.companion.playground.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PlaygroundSessionCreateDTO {
    @NotBlank
    private String profileId;
    private String profileVersionId;
    private Map<String, String> models;
    private String ttsVoiceId;
    private List<String> skillIds;
    private String systemPrompt;
    private String rolePrompt;
    @NotNull
    @Valid
    private PlaygroundVirtualDeviceDTO virtualDevice = new PlaygroundVirtualDeviceDTO();
}
