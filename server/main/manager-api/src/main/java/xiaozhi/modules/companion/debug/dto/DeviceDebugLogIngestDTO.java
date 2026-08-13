package xiaozhi.modules.companion.debug.dto;

import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogDraft;

@Data
public class DeviceDebugLogIngestDTO {
    @NotBlank
    @Size(max = 128)
    private String deviceRef;

    @Size(max = 128)
    private String sessionId;

    @Size(max = 128)
    private String sentenceId;

    @NotBlank
    @Pattern(regexp = "conversation|model_tool|audio|device")
    private String category;

    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    private String eventType;

    @NotBlank
    @Pattern(regexp = "debug|info|warning|error")
    private String level;

    @NotBlank
    @Size(max = 4000)
    private String summary;

    private Map<String, Object> details;
    private Long occurredAt;

    @Min(0)
    private Long durationMs;

    public DeviceDebugLogDraft toDraft() {
        return new DeviceDebugLogDraft(
                sessionId,
                sentenceId,
                category,
                eventType,
                level,
                summary,
                details,
                occurredAt,
                durationMs);
    }
}
