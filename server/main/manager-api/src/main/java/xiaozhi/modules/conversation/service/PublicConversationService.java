package xiaozhi.modules.conversation.service;

import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

public interface PublicConversationService {
    PublicConversationSessionVO create(Long userId, PublicConversationCreateDTO request);

    PublicConversationRuntimeBundleVO runtimeBundle(String conversationId);
}
