package zixuan.modules.companion.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminCompanionDeviceModeUpdateDTO {
    @NotBlank
    private String mode;
}
