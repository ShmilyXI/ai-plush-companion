package zixuan.modules.conversation.vo;

import java.time.Instant;
import java.util.Map;

public record PublicConversationEventVO(
        String type,
        String conversationId,
        String turnId,
        long sequence,
        Instant occurredAt,
        Map<String, Object> details) {
}
