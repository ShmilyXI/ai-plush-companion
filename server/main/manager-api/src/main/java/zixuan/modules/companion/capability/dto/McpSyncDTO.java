package zixuan.modules.companion.capability.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class McpSyncDTO {
    @NotBlank
    private String healthStatus;
    @Size(max = 128)
    private String errorClass;
    @Valid
    @NotNull
    private List<ToolDTO> tools = List.of();

    @Data
    public static class ToolDTO {
        @NotBlank
        @Size(max = 128)
        private String name;
        @NotNull
        private Map<String, Object> inputSchema;
    }
}
