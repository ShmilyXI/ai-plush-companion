package xiaozhi.modules.companion.model.dto;

import java.util.Map;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionProfileModelSaveDTO {
    @Pattern(regexp = "LLM|ASR|TTS|VAD|VLLM|Memory")
    private String modelType;
    @Pattern(regexp = "default|global|private")
    private String source;
    @Size(max = 32)
    private String resourceId;
    private Map<String, Object> overrides;
}
