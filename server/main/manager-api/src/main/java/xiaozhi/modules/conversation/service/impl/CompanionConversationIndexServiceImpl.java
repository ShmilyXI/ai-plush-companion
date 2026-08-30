package xiaozhi.modules.conversation.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.conversation.dao.CompanionConversationDao;
import xiaozhi.modules.conversation.dao.CompanionConversationTurnDao;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;

@Service
@AllArgsConstructor
public class CompanionConversationIndexServiceImpl implements CompanionConversationIndexService {
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_LIMIT = 100;
    private final CompanionConversationDao conversations;
    private final CompanionConversationTurnDao turns;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionConversationEntity create(Long ownerId, String profileId, Integer profileVersionNo, String source, String title) {
        return createWithId(ownerId, java.util.UUID.randomUUID().toString().replace("-", ""), profileId,
                profileVersionNo, source, title);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionConversationEntity createWithId(Long ownerId, String conversationId, String profileId,
            Integer profileVersionNo, String source, String title) {
        if (ownerId == null || profileId == null || profileId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            throw new RenException("会话身份不能为空");
        }
        Date now = new Date();
        CompanionConversationEntity entity = new CompanionConversationEntity();
        entity.setId(conversationId);
        entity.setOwnerId(ownerId);
        entity.setProfileId(profileId);
        entity.setProfileVersionNo(profileVersionNo == null ? 0 : profileVersionNo);
        entity.setSource("device".equalsIgnoreCase(source) ? "device" : "app");
        entity.setTitle(normalizeTitle(title));
        entity.setLastActivityAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (conversations.insert(entity) != 1) throw new RenException("会话创建失败");
        return entity;
    }

    @Override
    public List<CompanionConversationEntity> list(Long ownerId, int limit) {
        return conversations.selectRecent(ownerId, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    @Override
    @Transactional
    public CompanionConversationEntity requireReadable(Long ownerId, String conversationId) {
        CompanionConversationEntity entity = conversations.selectOwned(conversationId, ownerId);
        if (entity == null) throw new RenException("conversation_not_found");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long ownerId, String conversationId, String title) {
        requireReadable(ownerId, conversationId);
        String normalized = normalizeTitle(title);
        if (normalized.isBlank()) throw new RenException("会话标题不能为空");
        if (conversations.rename(conversationId, ownerId, normalized, new Date()) != 1) {
            throw new RenException("conversation_not_found");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long ownerId, String conversationId) {
        requireReadable(ownerId, conversationId);
        if (conversations.softDelete(conversationId, ownerId, new Date()) != 1) {
            throw new RenException("conversation_not_found");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean appendTurnIfAbsent(Long ownerId, String conversationId, String turnId, String requestId,
            String userText, String assistantText, String source, Date occurredAt) {
        CompanionConversationEntity conversation = requireReadable(ownerId, conversationId);
        if (turns.selectByConversationAndTurn(conversationId, turnId) != null) return false;
        if (turnId == null || turnId.isBlank() || userText == null || userText.isBlank()
                || assistantText == null || assistantText.isBlank() || occurredAt == null) {
            throw new RenException("会话轮次无效");
        }
        CompanionConversationTurnEntity turn = new CompanionConversationTurnEntity();
        turn.setConversationId(conversationId);
        turn.setTurnId(turnId);
        turn.setRequestId(requestId);
        turn.setSource("device".equalsIgnoreCase(source) ? "device" : "app");
        turn.setUserText(userText);
        turn.setAssistantText(assistantText);
        turn.setOccurredAt(occurredAt);
        turn.setCreatedAt(new Date());
        try {
            if (turns.insert(turn) != 1) throw new RenException("会话轮次写入失败");
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
        String title = conversation.getTitle();
        if (title == null || title.isBlank() || "新对话".equals(title)) title = normalizeTitle(userText);
        conversation.setTitle(title);
        conversation.setLastActivityAt(occurredAt);
        conversations.touch(conversationId, title, occurredAt, new Date());
        return true;
    }

    @Override
    @Transactional
    public List<CompanionConversationTurnEntity> history(Long ownerId, String conversationId) {
        requireReadable(ownerId, conversationId);
        return turns.selectByConversation(conversationId);
    }

    private String normalizeTitle(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() <= MAX_TITLE_LENGTH) return normalized;
        return normalized.substring(0, MAX_TITLE_LENGTH);
    }
}
