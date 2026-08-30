package xiaozhi.modules.conversation.service;

import java.util.Date;
import java.util.List;

import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;

public interface CompanionConversationIndexService {
    CompanionConversationEntity create(Long ownerId, String profileId, Integer profileVersionNo, String source, String title);

    CompanionConversationEntity createWithId(Long ownerId, String conversationId, String profileId,
            Integer profileVersionNo, String source, String title);

    List<CompanionConversationEntity> list(Long ownerId, int limit);

    CompanionConversationEntity requireReadable(Long ownerId, String conversationId);

    void rename(Long ownerId, String conversationId, String title);

    void softDelete(Long ownerId, String conversationId);

    boolean appendTurnIfAbsent(Long ownerId, String conversationId, String turnId, String requestId,
            String userText, String assistantText, String source, Date occurredAt);

    List<CompanionConversationTurnEntity> history(Long ownerId, String conversationId);
}
