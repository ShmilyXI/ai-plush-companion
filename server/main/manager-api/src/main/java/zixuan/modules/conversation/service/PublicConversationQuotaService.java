package zixuan.modules.conversation.service;

public interface PublicConversationQuotaService {
    void requireSession(Long userId, String apiKeyId);
}
