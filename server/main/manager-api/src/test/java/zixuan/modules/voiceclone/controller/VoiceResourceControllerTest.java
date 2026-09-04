package zixuan.modules.voiceclone.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import zixuan.common.exception.RenException;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.voiceclone.service.VoiceCloneService;

class VoiceResourceControllerTest {

    @Test
    void normalUserCanOnlyReadTheirOwnResources() {
        VoiceCloneService service = mock(VoiceCloneService.class);
        VoiceResourceController controller = new VoiceResourceController(service, mock(ModelConfigService.class));
        UserDetail user = user(7L, 0);
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            messages.when(() -> MessageUtils.getMessage(zixuan.common.exception.ErrorCode.VOICE_RESOURCE_NO_PERMISSION))
                    .thenReturn("no permission");
            controller.getByUserId(7L);
            RenException error = assertThrows(RenException.class, () -> controller.getByUserId(8L));
            org.junit.jupiter.api.Assertions.assertEquals(
                    zixuan.common.exception.ErrorCode.VOICE_RESOURCE_NO_PERMISSION, error.getCode());
        }
        verify(service).getByUserIdWithNames(7L);
    }

    @Test
    void superAdminCanReadAnotherUsersResources() {
        VoiceCloneService service = mock(VoiceCloneService.class);
        VoiceResourceController controller = new VoiceResourceController(service, mock(ModelConfigService.class));
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 1));
            controller.getByUserId(8L);
        }
        verify(service).getByUserIdWithNames(8L);
    }

    private UserDetail user(long id, int superAdmin) {
        UserDetail user = new UserDetail();
        user.setId(id);
        user.setSuperAdmin(superAdmin);
        return user;
    }
}
