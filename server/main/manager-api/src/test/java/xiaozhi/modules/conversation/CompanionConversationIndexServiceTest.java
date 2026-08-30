package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.dao.CompanionConversationDao;
import xiaozhi.modules.conversation.dao.CompanionConversationTurnDao;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;
import xiaozhi.modules.conversation.service.impl.CompanionConversationIndexServiceImpl;

class CompanionConversationIndexServiceTest {
    private final CompanionConversationDao conversations = mock(CompanionConversationDao.class);
    private final CompanionConversationTurnDao turns = mock(CompanionConversationTurnDao.class);
    private final CompanionConversationIndexServiceImpl service = new CompanionConversationIndexServiceImpl(conversations, turns);

    @Test
    void ownerCannotReadAnotherUsersConversation() {
        when(conversations.selectOwned("conversation-1", 10L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.requireReadable(10L, "conversation-1"));
        verify(turns, never()).selectByConversation(any());
    }

    @Test
    void duplicateTurnIsIdempotentAndDoesNotInsertTwice() {
        CompanionConversationEntity conversation = conversation(10L);
        when(conversations.selectOwned("conversation-1", 10L)).thenReturn(conversation);
        CompanionConversationTurnEntity existing = new CompanionConversationTurnEntity();
        existing.setConversationId("conversation-1");
        existing.setTurnId("turn-1");
        when(turns.selectByConversationAndTurn("conversation-1", "turn-1")).thenReturn(existing);

        assertEquals(false, service.appendTurnIfAbsent(10L, "conversation-1", "turn-1", "req-1", "你好", "嗨", "app", new Date()));
        verify(turns, never()).insert(any(CompanionConversationTurnEntity.class));
    }

    @Test
    void firstUserTextBecomesBoundedConversationTitle() {
        CompanionConversationEntity conversation = conversation(10L);
        when(conversations.selectOwned("conversation-1", 10L)).thenReturn(conversation);
        when(turns.selectByConversationAndTurn("conversation-1", "turn-1")).thenReturn(null);
        when(turns.insert(any(CompanionConversationTurnEntity.class))).thenReturn(1);
        when(conversations.updateById(any(CompanionConversationEntity.class))).thenReturn(1);

        service.appendTurnIfAbsent(10L, "conversation-1", "turn-1", "req-1", "第一次想和你聊聊今天的心情", "我在听", "app", new Date());

        verify(conversations).touch(eq("conversation-1"), eq("第一次想和你聊聊今天的心情"), any(Date.class), any(Date.class));
        assertEquals("第一次想和你聊聊今天的心情", conversation.getTitle());
    }

    private CompanionConversationEntity conversation(Long ownerId) {
        CompanionConversationEntity entity = new CompanionConversationEntity();
        entity.setId("conversation-1");
        entity.setOwnerId(ownerId);
        entity.setProfileId("profile-1");
        entity.setProfileVersionNo(1);
        entity.setTitle("");
        entity.setLastActivityAt(new Date());
        return entity;
    }
}
