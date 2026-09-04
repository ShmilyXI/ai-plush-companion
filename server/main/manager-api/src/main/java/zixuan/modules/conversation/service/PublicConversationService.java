package zixuan.modules.conversation.service;

import zixuan.modules.conversation.dto.PublicConversationCreateDTO;
import zixuan.modules.conversation.vo.PublicConversationSessionVO;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

public interface PublicConversationService {
    PublicConversationSessionVO create(Long userId, PublicConversationCreateDTO request);

    PublicConversationRuntimeBundleVO runtimeBundle(String conversationId);
}
