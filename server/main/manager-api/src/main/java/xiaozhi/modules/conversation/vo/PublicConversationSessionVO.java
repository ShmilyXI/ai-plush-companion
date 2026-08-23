package xiaozhi.modules.conversation.vo;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record PublicConversationSessionVO(
        String conversationId,
        String agentId,
        long agentVersion,
        String streamUrl,
        String runtimeToken,
        Instant expiresAt,
        Set<String> inputModes,
        Set<String> outputModes,
        Map<String, String> publicMetadata) {
}
