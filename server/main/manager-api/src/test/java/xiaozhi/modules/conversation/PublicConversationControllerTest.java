package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.controller.PublicConversationController;
import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.security.user.SecurityUser;

class PublicConversationControllerTest {
    @Test
    void createsSessionForCurrentUser() {
        PublicConversationService service = mock(PublicConversationService.class);
        PublicConversationController controller = new PublicConversationController(service);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");
        PublicConversationSessionVO expected = new PublicConversationSessionVO(
                "conversation-a", "agent-a", 4, "ws://runtime", "token", Instant.now(),
                Set.of("text"), Set.of("text"), Map.of());
        when(service.create(7L, request)).thenReturn(expected);

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            Result<PublicConversationSessionVO> result = controller.create(request);
            assertEquals(expected, result.getData());
        }
        verify(service).create(7L, request);
    }
}
