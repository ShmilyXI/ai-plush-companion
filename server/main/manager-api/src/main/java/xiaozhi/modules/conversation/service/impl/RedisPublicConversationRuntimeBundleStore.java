package xiaozhi.modules.conversation.service.impl;

import java.time.Duration;

import org.springframework.stereotype.Service;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.conversation.service.PublicConversationRuntimeBundleStore;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@Service
public class RedisPublicConversationRuntimeBundleStore implements PublicConversationRuntimeBundleStore {
    private static final String KEY_PREFIX = "public-conversation:runtime-bundle:";
    private final RedisUtils redis;

    public RedisPublicConversationRuntimeBundleStore(RedisUtils redis) {
        this.redis = redis;
    }

    @Override
    public void put(PublicConversationRuntimeBundleVO bundle, Duration ttl) {
        if (bundle == null || bundle.conversationId() == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("runtime bundle or ttl is invalid");
        }
        redis.set(key(bundle.conversationId()), JsonUtils.toJsonString(bundle), ttl.getSeconds());
    }

    @Override
    public PublicConversationRuntimeBundleVO get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        Object value = redis.get(key(conversationId));
        if (value == null) return null;
        return JsonUtils.parseObject(String.valueOf(value), PublicConversationRuntimeBundleVO.class);
    }

    @Override
    public void remove(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) redis.delete(key(conversationId));
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
