package xiaozhi.modules.companion.playground.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PlaygroundSessionVO(String sessionId, long snapshotVersion, Map<String, Object> effectiveConfig,
        String eventStreamPath, Instant expiresAt, List<PlaygroundEventVO> events) {
}
