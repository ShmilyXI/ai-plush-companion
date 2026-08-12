package xiaozhi.modules.companion.debug.model;

import java.util.Map;

public record DeviceDebugLogDraft(
        String sessionId,
        String sentenceId,
        String category,
        String eventType,
        String level,
        String summary,
        Map<String, Object> details,
        Long occurredAt,
        Long durationMs) {
}
