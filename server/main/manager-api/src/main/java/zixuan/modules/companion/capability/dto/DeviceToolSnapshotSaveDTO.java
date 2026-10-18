package zixuan.modules.companion.capability.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceToolSnapshotSaveDTO {
    @Size(max = 64)
    private String deviceModel;
    @Size(max = 64)
    private String firmwareVersion;
    @Valid
    @NotEmpty
    private List<ToolDTO> tools = List.of();

    @Data
    public static class ToolDTO {
        @NotBlank
        @Size(max = 128)
        private String name;
        @NotNull
        private Map<String, Object> inputSchema;
        private Boolean available = true;
    }
}
