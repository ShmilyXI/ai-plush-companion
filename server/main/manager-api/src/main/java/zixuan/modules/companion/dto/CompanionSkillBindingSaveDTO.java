package zixuan.modules.companion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionSkillBindingSaveDTO {
    @NotBlank
    @Size(max = 64)
    private String skillId;

    @Pattern(regexp = "LATEST|FIXED")
    private String versionMode = "LATEST";

    @Min(1)
    private Integer fixedVersion;

    @Size(max = 10000)
    private String overrideJson;

    @Min(-1000)
    @Max(1000)
    private Integer triggerPriority;

    private Boolean enabled = Boolean.TRUE;
}
