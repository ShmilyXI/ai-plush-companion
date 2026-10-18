package zixuan.modules.conversation.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import zixuan.common.utils.JsonUtils;
import zixuan.modules.conversation.service.PublicConversationHistoryStore;

@Service
public class RedisPublicConversationHistoryStore implements PublicConversationHistoryStore {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,100}");
    private static final String PREFIX = "public-conversation:history:";
    private static final int MAX_ITEMS = 50;
    private static final int MAX_TEXT_LENGTH = 12_000;
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redis;

    public RedisPublicConversationHistoryStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void append(String conversationId, Map<String, ?> item) {
        String key = key(conversationId);
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("turn_id", text(item == null ? null : item.get("turn_id"), 128));
        safe.put("text", text(item == null ? null : item.get("text"), MAX_TEXT_LENGTH));
        safe.put("reply", text(item == null ? null : item.get("reply"), MAX_TEXT_LENGTH));
        Object occurredAt = item == null ? null : item.get("occurred_at");
        if (!(occurredAt instanceof Number)) throw new IllegalArgumentException("历史时间无效");
        safe.put("occurred_at", ((Number) occurredAt).longValue());
        redis.opsForList().leftPush(key, JsonUtils.toJsonString(safe));
        redis.opsForList().trim(key, 0, MAX_ITEMS - 1);
        redis.expire(key, TTL);
    }

    @Override
    public List<Map<String, Object>> history(String conversationId, int limit) {
        if (limit <= 0) return List.of();
        int safeLimit = Math.min(limit, MAX_ITEMS);
        List<String> raw = redis.opsForList().range(key(conversationId), 0, safeLimit - 1);
        if (raw == null || raw.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = raw.size() - 1; index >= 0; index--) {
            try {
                Map<String, Object> item = JsonUtils.parseMap(raw.get(index));
                if (item != null) result.add(Map.copyOf(item));
            } catch (RuntimeException ignored) {
                // Skip a corrupt history item without exposing its raw value.
            }
        }
        return List.copyOf(result);
    }

    private String key(String conversationId) {
        if (StringUtils.isBlank(conversationId) || !ID.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("会话标识无效");
        }
        return PREFIX + conversationId;
    }

    private String text(Object value, int maxLength) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("历史文本无效");
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
