package xiaozhi.modules.conversation.service;

import java.util.List;
import java.util.Map;

public record PublicConversationCapabilityProjection(
        List<Map<String, Object>> skills,
        Map<String, Map<String, Object>> tools) {
    public static PublicConversationCapabilityProjection empty() {
        return new PublicConversationCapabilityProjection(List.of(), Map.of());
    }
}
