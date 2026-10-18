package zixuan.modules.companion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionProfileCreateDTO {
    @NotBlank
    @Size(max = 64)
    private String templateId;

    @NotBlank
    @Size(max = 64)
    private String name;
}
