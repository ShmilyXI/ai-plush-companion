package xiaozhi.modules.conversation.service;

import java.util.List;
import java.util.Set;

import xiaozhi.modules.conversation.dto.PublicConversationApiKeyCreateDTO;
import xiaozhi.modules.conversation.vo.PublicConversationApiKeyVO;

public interface PublicConversationApiKeyService {
    Set<String> SUPPORTED_SCOPES = Set.of(
            "conversation:text",
            "conversation:audio",
            "conversation:override",
            "resource:read",
            "device:control");

    PublicConversationApiKeyVO create(Long userId, PublicConversationApiKeyCreateDTO request);

    List<PublicConversationApiKeyVO> list(Long userId);

    void revoke(Long userId, String id);

    ResolvedApiKey resolve(String plaintextKey);

    ResolvedApiKey resolve(String plaintextKey, String source);

    record ResolvedApiKey(String id, Long userId, Set<String> scopes, Set<String> agentIds) {
    }
}
