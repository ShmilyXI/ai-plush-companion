package xiaozhi.modules.conversation.vo;

import java.util.Date;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicConversationApiKeyVO(
        String id,
        String name,
        String keyPrefix,
        Set<String> scopes,
        Set<String> agentIds,
        Date expiresAt,
        boolean revoked,
        Date lastUsedAt,
        Date createdAt,
        Date updatedAt,
        String createdSecret) {
}
