package zixuan.modules.conversation.service.impl;

import org.springframework.stereotype.Service;

import zixuan.common.redis.RedisUtils;
import zixuan.modules.conversation.service.PublicConversationApiKeyRateLimiter;

@Service
public class RedisPublicConversationApiKeyRateLimiter implements PublicConversationApiKeyRateLimiter {
    private static final long WINDOW_SECONDS = 60;
    private static final long MAX_FAILURES = 10;
    private final RedisUtils redis;

    public RedisPublicConversationApiKeyRateLimiter(RedisUtils redis) {
        this.redis = redis;
    }

    @Override
    public boolean allow(String source) {
        if (source == null || source.isBlank()) return true;
        Object value = redis.get(key(source));
        return value == null || Long.parseLong(String.valueOf(value)) < MAX_FAILURES;
    }

    @Override
    public void recordFailure(String source) {
        if (source == null || source.isBlank()) return;
        redis.increment(key(source), WINDOW_SECONDS);
    }

    private String key(String source) {
        return "public-conversation:api-key-failure:" + source.trim();
    }
}
