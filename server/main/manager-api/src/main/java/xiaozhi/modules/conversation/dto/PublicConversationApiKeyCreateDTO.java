package xiaozhi.modules.conversation.dto;

import java.util.Set;

import lombok.Data;

@Data
public class PublicConversationApiKeyCreateDTO {
    private String name;
    private Set<String> scopes;
    private Set<String> agentIds;
    private java.util.Date expiresAt;
}
