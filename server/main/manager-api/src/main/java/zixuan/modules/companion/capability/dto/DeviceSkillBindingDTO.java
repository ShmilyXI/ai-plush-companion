package zixuan.modules.companion.capability.dto;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class DeviceSkillBindingDTO {
    @NotBlank
    private String skillId;
    @NotBlank
    @Pattern(regexp = "LATEST|FIXED")
    private String versionMode;
    @Min(1)
    private Integer fixedVersion;
    private Boolean enabled;
    private Map<String, Object> overrides;
    @Min(-100000)
    @Max(100000)
    private Integer triggerPriority;
}
