package zixuan.modules.companion.capability.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CapabilitySecretSaveDTO {
    @Size(max = 16000)
    private String value;
}
