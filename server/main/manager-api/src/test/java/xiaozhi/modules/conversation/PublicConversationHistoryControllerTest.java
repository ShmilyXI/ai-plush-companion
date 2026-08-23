package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.controller.PublicConversationHistoryController;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.PublicConversationHistoryStore;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

class PublicConversationHistoryControllerTest {
    @Test
    void onlyOwnerWithResourceScopeCanReadHistory() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        PublicConversationHistoryStore history = mock(PublicConversationHistoryStore.class);
        PublicConversationAuthService auth = mock(PublicConversationAuthService.class);
        when(conversations.runtimeBundle("conversation-a")).thenReturn(
                new PublicConversationRuntimeBundleVO("conversation-a", 7L, "agent-a", 4, Map.of(), Map.of()));
        when(auth.current()).thenReturn(new PublicConversationAuthService.AuthenticatedCaller(
                7L, java.util.Set.of("resource:read"), java.util.Set.of("agent-a"), true, "key-a"));
        when(history.history("conversation-a", 10)).thenReturn(List.of(Map.of("text", "你好")));
        PublicConversationHistoryController controller = new PublicConversationHistoryController(conversations, history, auth);

        assertEquals("你好", controller.history("conversation-a", 10).getData().get(0).get("text"));
        verify(auth).requireScope(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("resource:read"));
    }

    @Test
    void rejectsDifferentOwnerBeforeReadingHistory() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        PublicConversationHistoryStore history = mock(PublicConversationHistoryStore.class);
        PublicConversationAuthService auth = mock(PublicConversationAuthService.class);
        when(conversations.runtimeBundle("conversation-a")).thenReturn(
                new PublicConversationRuntimeBundleVO("conversation-a", 7L, "agent-a", 4, Map.of(), Map.of()));
        when(auth.current()).thenReturn(new PublicConversationAuthService.AuthenticatedCaller(
                8L, java.util.Set.of("resource:read"), java.util.Set.of("agent-a"), true, "key-a"));
        PublicConversationHistoryController controller = new PublicConversationHistoryController(conversations, history, auth);

        assertThrows(IllegalArgumentException.class, () -> controller.history("conversation-a", 10));
        org.mockito.Mockito.verifyNoInteractions(history);
    }
}
