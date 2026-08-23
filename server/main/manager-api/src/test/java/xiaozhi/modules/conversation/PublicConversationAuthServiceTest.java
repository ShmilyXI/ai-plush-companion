package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import xiaozhi.modules.conversation.service.PublicConversationApiKeyService;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.impl.PublicConversationAuthServiceImpl;
import xiaozhi.modules.security.user.SecurityUser;

class PublicConversationAuthServiceTest {
    @Test
    void resolvesApiKeyWithoutReturningSecret() {
        PublicConversationApiKeyService keys = mock(PublicConversationApiKeyService.class);
        when(keys.resolve("pc_secret")).thenReturn(new PublicConversationApiKeyService.ResolvedApiKey(
                "key-a", 7L, Set.of("conversation:text"), Set.of("agent-a")));
        PublicConversationAuthService service = new PublicConversationAuthServiceImpl(keys);

        PublicConversationAuthService.AuthenticatedCaller caller = service.resolve("ApiKey pc_secret");

        assertEquals(7L, caller.userId());
        assertEquals("key-a", caller.keyId());
        assertTrue(caller.apiKey());
        assertTrue(caller.hasScope("conversation:text"));
        assertTrue(caller.canUseAgent("agent-a"));
        assertFalse(caller.canUseAgent("agent-b"));
    }

    @Test
    void bearerUsesCurrentUserAndFullFirstPartyScopes() {
        PublicConversationAuthService service = new PublicConversationAuthServiceImpl(mock(PublicConversationApiKeyService.class));
        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var caller = service.resolve("Bearer user-token");
            assertEquals(7L, caller.userId());
            assertFalse(caller.apiKey());
            assertTrue(caller.hasScope("device:control"));
        }
    }

    @Test
    void scopeAndAgentGuardsRejectApiKeyOutsideItsGrant() {
        PublicConversationAuthService service = new PublicConversationAuthServiceImpl(mock(PublicConversationApiKeyService.class));
        var caller = new PublicConversationAuthService.AuthenticatedCaller(7L,
                Set.of("conversation:text"), Set.of("agent-a"), true, "key-a");
        assertThrows(IllegalArgumentException.class, () -> service.requireScope(caller, "conversation:audio"));
        assertThrows(IllegalArgumentException.class, () -> service.requireAgent(caller, "agent-b"));
    }
}
