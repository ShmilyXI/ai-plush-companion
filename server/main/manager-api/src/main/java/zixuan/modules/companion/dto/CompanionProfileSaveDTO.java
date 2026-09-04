package zixuan.modules.companion.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import jakarta.validation.Valid;
import zixuan.modules.companion.model.dto.CompanionProfileModelSaveDTO;

@Data
public class CompanionProfileSaveDTO {
    @Size(min = 1, max = 64)
    private String agentName;

    @Pattern(regexp = "friend|lover")
    @Size(max = 16)
    private String relationMode;

    @Size(max = 64)
    private String userAddress;

    @Size(max = 1000)
    private String personality;

    @Size(max = 16000)
    private String systemPrompt;

    @Size(max = 32)
    private String ttsVoiceId;

    @Size(max = 10000)
    private String companionCueConfig;

    @Min(0)
    @Max(1)
    private Integer screenExpressionEnabled;

    @Min(0)
    @Max(1)
    private Integer cameraPreferenceEnabled;

    private List<@Valid CompanionProfileModelSaveDTO> models;

    private List<@Valid CompanionSkillBindingSaveDTO> skills;
}
