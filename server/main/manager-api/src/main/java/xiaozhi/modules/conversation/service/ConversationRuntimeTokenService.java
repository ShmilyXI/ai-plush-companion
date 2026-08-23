package xiaozhi.modules.conversation.service;

import java.time.Instant;
import java.util.Set;

public interface ConversationRuntimeTokenService {
    String issue(RuntimeTokenClaims claims);

    RuntimeTokenClaims verify(String token, Instant now);

    record RuntimeTokenClaims(
            String conversationId,
            String subject,
            String agentId,
            long agentVersion,
            Set<String> scopes,
            Set<String> inputModes,
            Set<String> outputModes,
            Instant issuedAt,
            Instant expiresAt) {
    }
}
