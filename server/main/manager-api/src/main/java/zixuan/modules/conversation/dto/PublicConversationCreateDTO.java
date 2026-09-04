package zixuan.modules.conversation.dto;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class PublicConversationCreateDTO {
    private static final Set<String> SUPPORTED_MODES = Set.of("text", "audio");

    @NotBlank(message = "Agent不能为空")
    private String agentId;

    @NotEmpty(message = "输入模式不能为空")
    private Set<String> inputModes = Set.of("text");

    @NotEmpty(message = "输出模式不能为空")
    private Set<String> outputModes = Set.of("text");

    private String voiceId;
    private Map<String, String> modelOverrides = Collections.emptyMap();

    public void validateModes() {
        if (inputModes == null || inputModes.isEmpty() || !SUPPORTED_MODES.containsAll(inputModes)) {
            throw new IllegalArgumentException("输入模式不受支持");
        }
        if (outputModes == null || outputModes.isEmpty() || !SUPPORTED_MODES.containsAll(outputModes)) {
            throw new IllegalArgumentException("输出模式不受支持");
        }
        if (modelOverrides == null) modelOverrides = Collections.emptyMap();
    }
}
