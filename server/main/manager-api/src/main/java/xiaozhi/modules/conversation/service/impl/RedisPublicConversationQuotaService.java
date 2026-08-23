package xiaozhi.modules.conversation.service.impl;

import org.springframework.stereotype.Service;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.conversation.service.PublicConversationQuotaService;

@Service
public class RedisPublicConversationQuotaService implements PublicConversationQuotaService {
    private static final long WINDOW_SECONDS = 60;
    private static final long MAX_SESSIONS_PER_WINDOW = 30;
    private final RedisUtils redis;

    public RedisPublicConversationQuotaService(RedisUtils redis) {
        this.redis = redis;
    }

    @Override
    public void requireSession(Long userId, String apiKeyId) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        String owner = apiKeyId == null || apiKeyId.isBlank() ? "user:" + userId : "key:" + apiKeyId;
        Long count = redis.increment("public-conversation:session-quota:" + owner, WINDOW_SECONDS);
        if (count != null && count > MAX_SESSIONS_PER_WINDOW) {
            throw new IllegalArgumentException("公共会话创建频率超出限制");
        }
    }
}
