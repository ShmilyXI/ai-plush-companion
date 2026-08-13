package xiaozhi.modules.volcengine.voice;

import java.util.List;

public record VolcengineVoiceDTO(
        String id,
        String name,
        String voiceType,
        String gender,
        String age,
        String languages,
        List<String> tags,
        String description,
        String trialUrl) {
}
