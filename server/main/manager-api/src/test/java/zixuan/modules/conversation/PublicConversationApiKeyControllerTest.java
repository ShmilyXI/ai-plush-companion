package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import zixuan.common.utils.Result;
import zixuan.modules.conversation.controller.PublicConversationApiKeyController;
import zixuan.modules.conversation.dto.PublicConversationApiKeyCreateDTO;
import zixuan.modules.conversation.service.PublicConversationApiKeyService;
import zixuan.modules.conversation.vo.PublicConversationApiKeyVO;
import zixuan.modules.security.user.SecurityUser;

class PublicConversationApiKeyControllerTest {
    @Test
    void managementEndpointsUseCurrentUser() {
        PublicConversationApiKeyService service = mock(PublicConversationApiKeyService.class);
        PublicConversationApiKeyController controller = new PublicConversationApiKeyController(service);
        PublicConversationApiKeyCreateDTO request = new PublicConversationApiKeyCreateDTO();
        PublicConversationApiKeyVO key = new PublicConversationApiKeyVO("key-a", "App", "pc_abcd",
                java.util.Set.of("conversation:text"), java.util.Set.of(), null, false, null, null, null, "pc_secret");
        when(service.create(7L, request)).thenReturn(key);
        when(service.list(7L)).thenReturn(List.of(key));

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            assertEquals(key, controller.create(request).getData());
            assertEquals(List.of(key), controller.list().getData());
            Result<Void> revoked = controller.revoke("key-a");
            assertEquals(0, revoked.getCode());
        }
        verify(service).revoke(7L, "key-a");
    }
}
