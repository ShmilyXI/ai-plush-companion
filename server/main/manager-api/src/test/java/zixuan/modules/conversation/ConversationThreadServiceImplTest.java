package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import zixuan.modules.conversation.controller.InternalPublicConversationController;
import zixuan.modules.conversation.dao.ConversationTurnDao;
import zixuan.modules.conversation.entity.ConversationTurnEntity;
import zixuan.modules.conversation.service.PublicConversationHistoryStore;
import zixuan.modules.conversation.service.PublicConversationService;
import zixuan.modules.conversation.service.impl.ConversationThreadServiceImpl;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

class ConversationThreadServiceImplTest {
    private final ConversationTurnDao dao = mock(ConversationTurnDao.class);
    private final ConversationThreadServiceImpl service = new ConversationThreadServiceImpl(dao);

    @Test
    void appendsATurnForAFirstPartySession() {
        service.append(bundle(true), turn("turn-1", "我的猫叫年糕", "年糕这名字真可爱"));

        ArgumentCaptor<ConversationTurnEntity> saved = ArgumentCaptor.forClass(ConversationTurnEntity.class);
        verify(dao).insert(saved.capture());
        assertEquals(7L, saved.getValue().getUserId());
        assertEquals("agent-a", saved.getValue().getAgentId());
        assertEquals("conversation-a", saved.getValue().getConversationId());
        assertEquals("turn-1", saved.getValue().getTurnId());
        assertEquals("我的猫叫年糕", saved.getValue().getUserText());
        assertEquals("年糕这名字真可爱", saved.getValue().getReplyText());
    }

    @Test
    void apiKeySessionsAndIncompleteTurnsAreNotRecorded() {
        service.append(bundle(false), turn("turn-1", "你好", "你好呀"));
        service.append(bundle(true), turn("turn-2", "你好", " "));
        service.append(bundle(true), turn(null, "你好", "你好呀"));
        service.append(null, turn("turn-3", "你好", "你好呀"));

        verify(dao, never()).insert(any(ConversationTurnEntity.class));
        assertFalse(service.enabled(bundle(false)));
        assertTrue(service.enabled(bundle(true)));
    }

    @Test
    void anOpeningLineIsStoredWithoutAnyUserText() {
        Map<String, Object> opening = turn("turn-1", "", "面试怎么样了？");
        opening.put("opening", true);

        service.append(bundle(true), opening);

        ArgumentCaptor<ConversationTurnEntity> saved = ArgumentCaptor.forClass(ConversationTurnEntity.class);
        verify(dao).insert(saved.capture());
        assertEquals("", saved.getValue().getUserText());
        assertEquals("面试怎么样了？", saved.getValue().getReplyText());
    }

    @Test
    void anOpeningLineStaysOutOfTheSessionHistoryButJoinsTheThread() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        PublicConversationHistoryStore history = mock(PublicConversationHistoryStore.class);
        zixuan.modules.conversation.service.ConversationThreadService threads =
                mock(zixuan.modules.conversation.service.ConversationThreadService.class);
        InternalPublicConversationController controller =
                new InternalPublicConversationController(conversations, history, threads);
        PublicConversationRuntimeBundleVO firstParty = bundle(true);
        when(conversations.runtimeBundle("first")).thenReturn(firstParty);
        Map<String, Object> opening = turn("turn-1", "", "面试怎么样了？");
        opening.put("opening", true);

        controller.appendHistory("first", opening);

        // 会话内的临时历史要求每一轮都有用户的话，开场白没有，所以不写进去。
        verify(history, never()).append(any(), any());
        verify(threads).append(firstParty, opening);
    }

    @Test
    void aRetriedTurnIsStoredOnce() {
        when(dao.insert(any(ConversationTurnEntity.class))).thenThrow(new DuplicateKeyException("uk_ai_conversation_turn"));

        service.append(bundle(true), turn("turn-1", "你好", "你好呀"));

        verify(dao).insert(any(ConversationTurnEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentTurnsComeBackOldestFirst() {
        when(dao.selectList(any(Wrapper.class))).thenReturn(List.of(
                entity("turn-3", "第三句", "回答三", 3000), entity("turn-2", "第二句", "回答二", 2000)));

        List<Map<String, Object>> recent = service.recent(7L, "agent-a", 2);

        assertEquals(List.of("turn-2", "turn-3"), recent.stream().map(item -> item.get("turn_id")).toList());
        assertEquals("第二句", recent.get(0).get("text"));
        assertEquals("回答二", recent.get(0).get("reply"));
        assertEquals(2000L, recent.get(0).get("occurred_at"));
        assertEquals(List.of(), service.recent(null, "agent-a", 2));
    }

    @Test
    void theRuntimeOnlyGetsAThreadForFirstPartySessions() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        ConversationThreadServiceImpl threads = mock(ConversationThreadServiceImpl.class);
        InternalPublicConversationController controller = new InternalPublicConversationController(
                conversations, mock(PublicConversationHistoryStore.class), threads);
        PublicConversationRuntimeBundleVO firstParty = bundle(true);
        PublicConversationRuntimeBundleVO apiKey = bundle(false);
        when(conversations.runtimeBundle("first")).thenReturn(firstParty);
        when(conversations.runtimeBundle("key")).thenReturn(apiKey);
        when(threads.enabled(firstParty)).thenReturn(true);
        when(threads.enabled(apiKey)).thenReturn(false);
        when(threads.recent(7L, "agent-a", 20)).thenReturn(List.of(Map.of("turn_id", "turn-1")));

        assertEquals(1, controller.thread("first", 20).getData().size());
        assertEquals(0, controller.thread("key", 20).getData().size());

        controller.appendHistory("first", turn("turn-9", "你好", "你好呀"));
        verify(threads).append(firstParty, turn("turn-9", "你好", "你好呀"));
    }

    private PublicConversationRuntimeBundleVO bundle(boolean threadEnabled) {
        return new PublicConversationRuntimeBundleVO("conversation-a", 7L, "agent-a", 1,
                Map.of("historyThread", threadEnabled), Map.of());
    }

    private Map<String, Object> turn(String turnId, String text, String reply) {
        Map<String, Object> item = new HashMap<>();
        item.put("turn_id", turnId);
        item.put("text", text);
        item.put("reply", reply);
        return item;
    }

    private ConversationTurnEntity entity(String turnId, String text, String reply, long time) {
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setTurnId(turnId);
        entity.setUserText(text);
        entity.setReplyText(reply);
        entity.setCreatedAt(new Date(time));
        return entity;
    }
}
