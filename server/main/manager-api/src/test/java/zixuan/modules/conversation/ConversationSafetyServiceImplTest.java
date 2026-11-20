package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.modules.conversation.dao.SafetyEventDao;
import zixuan.modules.conversation.entity.SafetyEventEntity;
import zixuan.modules.conversation.service.impl.ConversationSafetyServiceImpl;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import zixuan.modules.sys.service.SysParamsService;

class ConversationSafetyServiceImplTest {
    private final SafetyEventDao dao = mock(SafetyEventDao.class);
    private final SysParamsService params = mock(SysParamsService.class);
    private final ConversationSafetyServiceImpl service = new ConversationSafetyServiceImpl(dao, params);

    @Test
    void operatorTermsAreSplitTrimmedAndDeduplicated() {
        when(params.getValue(ConversationSafetyServiceImpl.BLOCKED_TERMS_PARAM, true))
                .thenReturn(" 词语一, 词语二，词语一\n词语三 ,, 单 ,");

        assertEquals(List.of("词语一", "词语二", "词语三"), service.blockedTerms());
    }

    @Test
    void anUnsetParameterMeansNoExtraTerms() {
        when(params.getValue(ConversationSafetyServiceImpl.BLOCKED_TERMS_PARAM, true)).thenReturn(null, "", "null");

        for (int index = 0; index < 3; index++) {
            assertEquals(List.of(), service.blockedTerms());
        }
    }

    @Test
    void recordsTheCategoryAndActionOfAnEvent() {
        service.record(bundle(), event("input", "crisis", "self_harm"));

        ArgumentCaptor<SafetyEventEntity> saved = ArgumentCaptor.forClass(SafetyEventEntity.class);
        verify(dao).insert(saved.capture());
        assertEquals(7L, saved.getValue().getUserId());
        assertEquals("agent-a", saved.getValue().getAgentId());
        assertEquals("turn-1", saved.getValue().getTurnId());
        assertEquals("input", saved.getValue().getDirection());
        assertEquals("crisis", saved.getValue().getAction());
        assertEquals("self_harm", saved.getValue().getCategory());
    }

    @Test
    void ignoresEventsThatAreNotInTheExpectedVocabulary() {
        service.record(bundle(), event("sideways", "block", "self_harm"));
        service.record(bundle(), event("input", "allow", "self_harm"));
        service.record(bundle(), event("input", "block", "用户说了一段很长的原话"));
        service.record(bundle(), event("input", "block", null));
        service.record(null, event("input", "block", "self_harm"));

        verify(dao, never()).insert(any(SafetyEventEntity.class));
    }

    private PublicConversationRuntimeBundleVO bundle() {
        return new PublicConversationRuntimeBundleVO("conversation-a", 7L, "agent-a", 1, Map.of(), Map.of());
    }

    private Map<String, Object> event(String direction, String action, String category) {
        Map<String, Object> event = new HashMap<>();
        event.put("turn_id", "turn-1");
        event.put("direction", direction);
        event.put("action", action);
        event.put("category", category);
        return event;
    }
}
