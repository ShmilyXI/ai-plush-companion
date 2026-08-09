package xiaozhi.modules.companion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionPlanSaveDTO {
    @NotBlank
    @Size(max = 32)
    private String planCode;

    @NotBlank
    @Size(max = 64)
    private String planName;

    @NotNull
    @Min(0)
    private Integer maxDevices;

    @NotNull
    @Min(0)
    private Integer maxProfiles;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer longTermMemory;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer advancedVoice;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
