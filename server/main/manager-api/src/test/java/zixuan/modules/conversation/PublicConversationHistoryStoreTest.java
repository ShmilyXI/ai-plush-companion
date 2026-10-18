package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import zixuan.modules.conversation.service.impl.RedisPublicConversationHistoryStore;

class PublicConversationHistoryStoreTest {
    @SuppressWarnings("unchecked")
    @Test
    void storesOnlyAllowlistedHistoryFieldsAndCapsReads() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        RedisPublicConversationHistoryStore store = new RedisPublicConversationHistoryStore(redis);

        store.append("conversation-a", Map.of("turn_id", "turn-a", "text", "你好", "reply", "你好呀",
                "occurred_at", 1_800_000_000_000L, "secret", "must-not-store"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(lists).leftPush(eq("public-conversation:history:conversation-a"), payload.capture());
        assertEquals("turn-a", zixuan.common.utils.JsonUtils.parseMap(payload.getValue()).get("turn_id"));
        assertEquals(null, zixuan.common.utils.JsonUtils.parseMap(payload.getValue()).get("secret"));
        verify(lists).trim("public-conversation:history:conversation-a", 0, 49);
        verify(redis).expire(eq("public-conversation:history:conversation-a"), eq(java.time.Duration.ofHours(24)));
    }

    @Test
    void rejectsInvalidConversationIds() {
        RedisPublicConversationHistoryStore store = new RedisPublicConversationHistoryStore(mock(StringRedisTemplate.class));
        assertThrows(IllegalArgumentException.class, () -> store.history("../secret", 20));
    }
}
