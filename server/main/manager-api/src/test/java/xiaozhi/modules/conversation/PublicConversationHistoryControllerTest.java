package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.controller.PublicConversationHistoryController;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.PublicConversationHistoryStore;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
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

    @Test
    void bearerUsersCanReadDurableHistoryAfterRuntimeBundleExpires() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        PublicConversationHistoryStore history = mock(PublicConversationHistoryStore.class);
        PublicConversationAuthService auth = mock(PublicConversationAuthService.class);
        CompanionConversationIndexService index = mock(CompanionConversationIndexService.class);
        when(conversations.runtimeBundle("conversation-a"))
                .thenThrow(new IllegalArgumentException("会话不存在或已过期"));
        when(auth.current()).thenReturn(new PublicConversationAuthService.AuthenticatedCaller(
                7L, java.util.Set.of(), java.util.Set.of(), false, null));
        CompanionConversationEntity conversation = new CompanionConversationEntity();
        conversation.setId("conversation-a");
        conversation.setOwnerId(7L);
        conversation.setProfileId("agent-a");
        when(index.requireReadable(7L, "conversation-a")).thenReturn(conversation);
        var turn = new xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity();
        turn.setUserText("你好");
        turn.setAssistantText("你好呀");
        when(index.history(7L, "conversation-a")).thenReturn(List.of(turn));

        PublicConversationHistoryController controller = new PublicConversationHistoryController(
                conversations, history, auth, index);

        assertEquals("你好", controller.history("conversation-a", 10).getData().get(0).get("text"));
        verifyNoInteractions(history);
    }

    @Test
    void apiKeyCanReadDurableHistoryAfterRuntimeBundleExpires() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        PublicConversationHistoryStore history = mock(PublicConversationHistoryStore.class);
        PublicConversationAuthService auth = mock(PublicConversationAuthService.class);
        CompanionConversationIndexService index = mock(CompanionConversationIndexService.class);
        when(conversations.runtimeBundle("conversation-a"))
                .thenThrow(new IllegalArgumentException("会话不存在或已过期"));
        when(auth.current()).thenReturn(new PublicConversationAuthService.AuthenticatedCaller(
                7L, java.util.Set.of("resource:read"), java.util.Set.of("agent-a"), true, "key-a"));
        CompanionConversationEntity conversation = new CompanionConversationEntity();
        conversation.setId("conversation-a");
        conversation.setOwnerId(7L);
        conversation.setProfileId("agent-a");
        when(index.requireReadable(7L, "conversation-a")).thenReturn(conversation);
        var turn = new xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity();
        turn.setUserText("你好");
        turn.setAssistantText("你好呀");
        when(index.history(7L, "conversation-a")).thenReturn(List.of(turn));

        PublicConversationHistoryController controller = new PublicConversationHistoryController(
                conversations, history, auth, index);

        assertEquals("你好", controller.history("conversation-a", 10).getData().get(0).get("text"));
        verify(auth).requireScope(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("resource:read"));
        verifyNoInteractions(history);
    }
}
