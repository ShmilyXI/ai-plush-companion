package xiaozhi.modules.conversation.security;

import java.util.Set;

import xiaozhi.common.user.UserDetail;

public class PublicConversationUserDetail extends UserDetail {
    private Set<String> scopes = Set.of();
    private Set<String> agentIds = Set.of();
    private String apiKeyId;

    public Set<String> getScopes() {
        return scopes;
    }

    public void setScopes(Set<String> scopes) {
        this.scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public Set<String> getAgentIds() {
        return agentIds;
    }

    public void setAgentIds(Set<String> agentIds) {
        this.agentIds = agentIds == null ? Set.of() : Set.copyOf(agentIds);
    }

    public String getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(String apiKeyId) {
        this.apiKeyId = apiKeyId;
    }
}
