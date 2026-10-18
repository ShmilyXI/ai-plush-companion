package zixuan.modules.companion.debug.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeviceDebugLogSettingDTO {
    @NotNull
    private Boolean enabled;
}
