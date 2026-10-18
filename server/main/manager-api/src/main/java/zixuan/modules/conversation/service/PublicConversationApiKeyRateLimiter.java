package zixuan.modules.conversation.service;

public interface PublicConversationApiKeyRateLimiter {
    boolean allow(String source);

    void recordFailure(String source);
}
