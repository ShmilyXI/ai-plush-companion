package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.modules.agent.service.AgentService;
import zixuan.modules.conversation.dao.PublicConversationApiKeyDao;
import zixuan.modules.conversation.dto.PublicConversationApiKeyCreateDTO;
import zixuan.modules.conversation.entity.PublicConversationApiKeyEntity;
import zixuan.modules.conversation.service.PublicConversationApiKeyService;
import zixuan.modules.conversation.service.PublicConversationApiKeyRateLimiter;
import zixuan.modules.conversation.service.impl.PublicConversationApiKeyServiceImpl;
import zixuan.modules.companion.service.CompanionAuditService;

class PublicConversationApiKeyServiceTest {
    @Test
    void createsOneTimeSecretAndStoresOnlyHash() {
        PublicConversationApiKeyDao dao = mock(PublicConversationApiKeyDao.class);
        AgentService agents = mock(AgentService.class);
        when(agents.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(dao.insert(any(PublicConversationApiKeyEntity.class))).thenAnswer(invocation -> {
            ((PublicConversationApiKeyEntity) invocation.getArgument(0)).setId("key-a");
            return 1;
        });
        PublicConversationApiKeyService service = new PublicConversationApiKeyServiceImpl(dao, agents);
        PublicConversationApiKeyCreateDTO request = new PublicConversationApiKeyCreateDTO();
        request.setName("App key");
        request.setScopes(Set.of("conversation:text"));
        request.setAgentIds(Set.of("agent-a"));

        var result = service.create(7L, request);

        assertNotNull(result.createdSecret());
        assertTruePrefix(result.createdSecret());
        ArgumentCaptor<PublicConversationApiKeyEntity> captured = ArgumentCaptor.forClass(PublicConversationApiKeyEntity.class);
        verify(dao).insert(captured.capture());
        PublicConversationApiKeyEntity entity = captured.getValue();
        assertEquals(64, entity.getKeyHash().length());
        assertFalse(entity.getKeyHash().equals(result.createdSecret()));
        assertEquals("key-a", result.id());
    }

    @Test
    void listNeverReturnsOriginalSecret() {
        PublicConversationApiKeyDao dao = mock(PublicConversationApiKeyDao.class);
        PublicConversationApiKeyEntity entity = new PublicConversationApiKeyEntity();
        entity.setId("key-a"); entity.setUserId(7L); entity.setName("App key");
        entity.setKeyPrefix("pc_abcd"); entity.setKeyHash("a".repeat(64)); entity.setScopesJson("[\"conversation:text\"]");
        when(dao.selectByUser(7L)).thenReturn(List.of(entity));

        var result = new PublicConversationApiKeyServiceImpl(dao, mock(AgentService.class)).list(7L);

        assertEquals(1, result.size());
        assertNull(result.get(0).createdSecret());
        assertEquals("pc_abcd", result.get(0).keyPrefix());
    }

    @Test
    void revokedOrExpiredKeyCannotResolve() {
        PublicConversationApiKeyDao dao = mock(PublicConversationApiKeyDao.class);
        PublicConversationApiKeyEntity entity = new PublicConversationApiKeyEntity();
        entity.setUserId(7L); entity.setKeyHash(hash("secret")); entity.setScopesJson("[\"conversation:text\"]");
        entity.setAgentIdsJson("[]"); entity.setRevoked(1); entity.setExpiresAt(Date.from(Instant.now().plusSeconds(900)));
        when(dao.selectByHash(hash("secret"))).thenReturn(entity);
        var service = new PublicConversationApiKeyServiceImpl(dao, mock(AgentService.class));

        assertThrows(IllegalArgumentException.class, () -> service.resolve("secret"));
        entity.setRevoked(0); entity.setExpiresAt(Date.from(Instant.now().minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("secret"));
    }

    @Test
    void failedResolutionIsRateLimitedAndAuditedWithoutSecret() {
        PublicConversationApiKeyDao dao = mock(PublicConversationApiKeyDao.class);
        CompanionAuditService audit = mock(CompanionAuditService.class);
        PublicConversationApiKeyRateLimiter limiter = mock(PublicConversationApiKeyRateLimiter.class);
        when(limiter.allow("127.0.0.1")).thenReturn(true);
        when(dao.selectByHash(any())).thenReturn(null);
        var service = new PublicConversationApiKeyServiceImpl(dao, mock(AgentService.class), audit, limiter);

        assertThrows(IllegalArgumentException.class, () -> service.resolve("wrong-secret", "127.0.0.1"));

        verify(limiter).recordFailure("127.0.0.1");
        verify(audit).record(org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("public-api-key.auth-failed"),
                org.mockito.ArgumentMatchers.eq("public-api-key"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(dao, never()).updateById(any(PublicConversationApiKeyEntity.class));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void assertTruePrefix(String value) {
        if (value == null || !value.startsWith("pc_") || value.length() < 40) throw new AssertionError("invalid api key");
    }
}
