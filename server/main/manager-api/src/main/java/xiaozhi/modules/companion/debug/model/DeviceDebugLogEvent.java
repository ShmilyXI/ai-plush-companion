package xiaozhi.modules.companion.debug.model;

import java.util.Map;

public record DeviceDebugLogEvent(
        String cursor,
        String deviceId,
        long occurredAt,
        long receivedAt,
        String sessionId,
        String sentenceId,
        String category,
        String eventType,
        String level,
        String summary,
        Map<String, Object> details,
        Long durationMs) {
}
