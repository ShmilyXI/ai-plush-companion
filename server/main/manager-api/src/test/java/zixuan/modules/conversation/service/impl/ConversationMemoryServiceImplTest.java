package zixuan.modules.conversation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import zixuan.modules.conversation.dao.ConversationMemoryDao;
import zixuan.modules.conversation.entity.ConversationMemoryEntity;
import zixuan.modules.conversation.service.ConversationMemoryService;

class ConversationMemoryServiceImplTest {

    private static ConversationMemoryEntity stored(long id, String content) {
        ConversationMemoryEntity entity = new ConversationMemoryEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setAgentId("agent-a");
        entity.setContent(content);
        entity.setSource(ConversationMemoryService.FROM_CONVERSATION);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static ConversationMemoryDao daoWith(List<ConversationMemoryEntity> existing) {
        ConversationMemoryDao dao = mock(ConversationMemoryDao.class);
        when(dao.selectList(any(Wrapper.class))).thenReturn(existing);
        return dao;
    }

    @Test
    void remembersNewThingsAndSkipsWhatItAlreadyKnows() {
        ConversationMemoryDao dao = daoWith(List.of(stored(1, "养了一只猫，叫年糕")));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(dao);

        // 第二条和已有的只差标点和空格，算同一件事；空的不记；同一批里重复的只记一次。
        int added = service.remember(7L, "agent-a",
                List.of("在准备下个月的面试", "养了一只猫 叫年糕。", "  ", "在准备下个月的面试"),
                ConversationMemoryService.FROM_CONVERSATION);

        assertEquals(1, added);
        ArgumentCaptor<ConversationMemoryEntity> saved = ArgumentCaptor.forClass(ConversationMemoryEntity.class);
        verify(dao, times(1)).insert(saved.capture());
        assertEquals("在准备下个月的面试", saved.getValue().getContent());
        assertEquals(7L, saved.getValue().getUserId());
        assertEquals("agent-a", saved.getValue().getAgentId());
        assertEquals("conversation", saved.getValue().getSource());
    }

    @Test
    void keepsWhoWroteItAndCutsOverlongText() {
        ConversationMemoryDao dao = daoWith(List.of());
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(dao);

        service.remember(7L, "agent-a", List.of("好".repeat(500)), ConversationMemoryService.FROM_USER);

        ArgumentCaptor<ConversationMemoryEntity> saved = ArgumentCaptor.forClass(ConversationMemoryEntity.class);
        verify(dao).insert(saved.capture());
        assertEquals(ConversationMemoryService.MAX_LENGTH, saved.getValue().getContent().length());
        assertEquals("user", saved.getValue().getSource());
    }

    @Test
    void dropsTheOldestOnceItIsFull() {
        List<ConversationMemoryEntity> existing = new ArrayList<>();
        for (long id = 1; id <= ConversationMemoryService.MAX_ITEMS; id++) {
            existing.add(stored(id, "第 " + id + " 件事"));
        }
        ConversationMemoryDao dao = daoWith(existing);
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(dao);

        assertEquals(2, service.remember(7L, "agent-a", List.of("新的一件事", "又一件新的事"),
                ConversationMemoryService.FROM_CONVERSATION));

        verify(dao).deleteById(1L);
        verify(dao).deleteById(2L);
        verify(dao, never()).deleteById(3L);
    }

    @Test
    void doesNothingWithoutAnOwner() {
        ConversationMemoryDao dao = daoWith(List.of());
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(dao);

        assertEquals(0, service.remember(null, "agent-a", List.of("一件事"), "conversation"));
        assertEquals(0, service.remember(7L, " ", List.of("一件事"), "conversation"));
        assertFalse(service.forget(null, "agent-a", 1L));
        assertFalse(service.forget(7L, "agent-a", null));
        assertEquals(0, service.clear(7L, null));
        verify(dao, never()).insert(any(ConversationMemoryEntity.class));
    }
}
