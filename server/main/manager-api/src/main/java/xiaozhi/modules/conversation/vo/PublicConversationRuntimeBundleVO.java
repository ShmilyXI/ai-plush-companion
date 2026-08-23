package xiaozhi.modules.conversation.vo;

import java.util.Map;

public record PublicConversationRuntimeBundleVO(
        String conversationId,
        Long ownerId,
        String agentId,
        long agentVersion,
        Map<String, Object> config,
        Map<String, Map<String, Object>> runtimeModels) {
}
