package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.conversation.controller.InternalPublicConversationController;
import zixuan.modules.conversation.dao.ConversationUsageDao;
import zixuan.modules.conversation.entity.ConversationUsageEntity;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.service.ConversationUsageService;
import zixuan.modules.conversation.service.PublicConversationHistoryStore;
import zixuan.modules.conversation.service.PublicConversationService;
import zixuan.modules.conversation.service.impl.ConversationUsageServiceImpl;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import zixuan.modules.sys.service.SysParamsService;

class ConversationUsageServiceImplTest {
    private final ConversationUsageDao dao = mock(ConversationUsageDao.class);
    private final SysParamsService params = mock(SysParamsService.class);
    private final ConversationUsageServiceImpl service = new ConversationUsageServiceImpl(dao, params);

    @BeforeAll
    static void injectMessageSource() {
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", mock(MessageSource.class));
    }

    @Test
    void recordsWhatATurnUsedAndReportsWhatIsLeftToday() {
        limit("200");
        when(dao.countFirstPartyTurns(eq(7L), any(LocalDate.class))).thenReturn(41L);

        Integer remaining = service.record(bundle(true), turn());

        assertEquals(159, remaining);
        ArgumentCaptor<ConversationUsageEntity> saved = ArgumentCaptor.forClass(ConversationUsageEntity.class);
        verify(dao).insert(saved.capture());
        ConversationUsageEntity row = saved.getValue();
        assertEquals(7L, row.getUserId());
        assertEquals("agent-a", row.getAgentId());
        assertEquals(1, row.getFirstParty());
        assertEquals("audio", row.getInputMode());
        assertEquals("deepseek-chat", row.getLlmModel());
        assertEquals(321, row.getPromptTokens());
        assertEquals(12, row.getCompletionTokens());
        assertEquals(5, row.getInputChars());
        assertEquals(9, row.getReplyChars());
        assertEquals(640, row.getFirstTextMs());
    }

    @Test
    void missingOrMalformedNumbersAreStoredAsUnknownNotGuessed() {
        limit("200");
        Map<String, Object> item = turn();
        item.put("usage", Map.of("input_mode", "telepathy", "prompt_tokens", "many", "total_ms", -5));

        service.record(bundle(true), item);

        ArgumentCaptor<ConversationUsageEntity> saved = ArgumentCaptor.forClass(ConversationUsageEntity.class);
        verify(dao).insert(saved.capture());
        assertEquals("text", saved.getValue().getInputMode());
        assertNull(saved.getValue().getPromptTokens());
        assertNull(saved.getValue().getTotalMs());
        assertEquals(0, saved.getValue().getReplyChars());
    }

    @Test
    void apiKeySessionsAreMeteredButNotHeldToTheDailyLimit() {
        limit("200");

        assertNull(service.record(bundle(false), turn()));

        ArgumentCaptor<ConversationUsageEntity> saved = ArgumentCaptor.forClass(ConversationUsageEntity.class);
        verify(dao).insert(saved.capture());
        assertEquals(0, saved.getValue().getFirstParty());
        verify(dao, never()).countFirstPartyTurns(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOpeningLineIsMeteredAsAnOpeningNotAsAUserTurn() {
        limit("200");
        Map<String, Object> item = turn();
        ((Map<String, Object>) item.get("usage")).put("input_mode", "opening");

        service.record(bundle(true), item);

        ArgumentCaptor<ConversationUsageEntity> saved = ArgumentCaptor.forClass(ConversationUsageEntity.class);
        verify(dao).insert(saved.capture());
        // 每日次数的统计按这个值把开场白排除在外。
        assertEquals("opening", saved.getValue().getInputMode());
    }

    @Test
    void aRetriedTurnIsCountedOnce() {
        limit("200");
        when(dao.insert(any(ConversationUsageEntity.class))).thenThrow(new DuplicateKeyException("uk"));
        when(dao.countFirstPartyTurns(eq(7L), any(LocalDate.class))).thenReturn(10L);

        assertEquals(190, service.record(bundle(true), turn()));
    }

    @Test
    void theLimitStopsNewSessionsAndCapsWhatOneSessionMayUse() {
        limit("200");
        when(dao.countFirstPartyTurns(eq(7L), any(LocalDate.class))).thenReturn(0L, 150L, 200L, 230L);

        assertEquals(ConversationUsageService.SESSION_TURN_LIMIT, service.requireTurnBudget(7L));
        assertEquals(50, service.requireTurnBudget(7L));
        assertEquals(ErrorCode.DAILY_TURN_LIMIT_REACHED,
                assertThrows(RenException.class, () -> service.requireTurnBudget(7L)).getCode());
        assertEquals(0, service.remainingToday(7L));
    }

    @Test
    void zeroBlankOrUnreadableLimitMeansUnlimited() {
        for (String value : new String[] { "0", "", null, "lots", "-3" }) {
            limit(value);
            assertEquals(Integer.MAX_VALUE, service.remainingToday(7L), String.valueOf(value));
            assertEquals(ConversationUsageService.SESSION_TURN_LIMIT, service.requireTurnBudget(7L));
        }
        limit("0");
        assertNull(service.record(bundle(true), turn()));
    }

    @Test
    void theRuntimeLearnsTheRemainingAllowanceAndUsageStaysOutOfTheHistory() {
        PublicConversationService conversations = mock(PublicConversationService.class);
        PublicConversationHistoryStore history = mock(PublicConversationHistoryStore.class);
        ConversationThreadService threads = mock(ConversationThreadService.class);
        ConversationUsageService usage = mock(ConversationUsageService.class);
        InternalPublicConversationController controller =
                new InternalPublicConversationController(conversations, history, threads, usage);
        PublicConversationRuntimeBundleVO bundle = bundle(true);
        Map<String, Object> item = turn();
        when(conversations.runtimeBundle("c")).thenReturn(bundle);
        when(usage.record(bundle, item)).thenReturn(0);

        Map<String, Object> result = controller.appendHistory("c", item).getData();

        assertEquals(0, result.get("remaining_turns"));
        Map<String, Object> stored = new HashMap<>(item);
        stored.remove("usage");
        verify(history).append("c", stored);
        verify(threads).append(bundle, stored);
    }

    private void limit(String value) {
        when(params.getValue(ConversationUsageServiceImpl.DAILY_LIMIT_PARAM, true)).thenReturn(value);
    }

    private PublicConversationRuntimeBundleVO bundle(boolean firstParty) {
        return new PublicConversationRuntimeBundleVO("conversation-a", 7L, "agent-a", 1,
                Map.of("historyThread", firstParty), Map.of());
    }

    private Map<String, Object> turn() {
        Map<String, Object> usage = new HashMap<>();
        usage.put("input_mode", "audio");
        usage.put("llm_model", "deepseek-chat");
        usage.put("prompt_tokens", 321);
        usage.put("completion_tokens", 12);
        usage.put("input_chars", 5);
        usage.put("reply_chars", 9);
        usage.put("tts_chars", 9);
        usage.put("first_text_ms", 640);
        usage.put("total_ms", 2100);
        Map<String, Object> item = new HashMap<>();
        item.put("turn_id", "turn-1");
        item.put("text", "今天好累呀");
        item.put("reply", "辛苦了，歇一会儿吧");
        item.put("usage", usage);
        return item;
    }
}
