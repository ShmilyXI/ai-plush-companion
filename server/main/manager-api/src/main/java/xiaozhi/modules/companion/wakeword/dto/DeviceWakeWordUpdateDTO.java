package xiaozhi.modules.companion.wakeword.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceWakeWordUpdateDTO {
    @NotBlank
    @Size(max = 32)
    private String word;
}
