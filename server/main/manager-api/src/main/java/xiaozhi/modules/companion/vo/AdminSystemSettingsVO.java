package xiaozhi.modules.companion.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminSystemSettingsVO {
    private String publicWebsocketUrl;
    private String publicOtaUrl;
    private String xiaozhiListenHost;
    private Integer xiaozhiListenPort;
    private String otaListenHost;
    private Integer otaListenPort;
    private String defaultLlmModelId;
    private String defaultVllmModelId;
    private String defaultTtsModelId;
    private String defaultAsrModelId;
    private String defaultVadModelId;
    private String defaultMemoryModelId;
    private String defaultTtsVoiceId;
    private String proactivePlannerPrompt;
    private Map<String, List<Option>> modelOptions;
    private List<Option> voices;
    private Map<String, Health> health;
    private boolean restartRequired;
    private List<String> restartServices;

    public record Option(String id, String name, String type) {
    }

    public record Health(String status, String address, Instant checkedAt) {
    }
}
