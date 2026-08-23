package xiaozhi.modules.conversation.service;

import java.util.Set;

public interface PublicConversationAuthService {
    AuthenticatedCaller resolve(String authorizationHeader);

    AuthenticatedCaller current();

    void requireScope(AuthenticatedCaller caller, String scope);

    void requireAgent(AuthenticatedCaller caller, String agentId);

    record AuthenticatedCaller(Long userId, Set<String> scopes, Set<String> agentIds, boolean apiKey, String keyId) {
        public boolean hasScope(String scope) {
            return scopes != null && scopes.contains(scope);
        }

        public boolean canUseAgent(String agentId) {
            return agentIds == null || agentIds.isEmpty() || agentIds.contains(agentId);
        }
    }
}
