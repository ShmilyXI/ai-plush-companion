package xiaozhi.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import xiaozhi.modules.companion.controller.CompanionDeviceController;
import xiaozhi.modules.companion.service.CompanionDeviceService;
import xiaozhi.modules.companion.wakeword.dto.DeviceWakeWordUpdateDTO;
import xiaozhi.modules.companion.wakeword.service.DeviceWakeWordService;
import xiaozhi.modules.companion.wakeword.vo.DeviceWakeWordVO;
import xiaozhi.modules.security.user.SecurityUser;

class DeviceWakeWordControllerTest {
    @Test
    void exposesOwnerScopedGetUpdateAndRetryRoutes() throws Exception {
        assertNotNull(CompanionDeviceController.class.getMethod("getWakeWord", String.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(CompanionDeviceController.class
                .getMethod("updateWakeWord", String.class, DeviceWakeWordUpdateDTO.class)
                .getAnnotation(PutMapping.class));
        assertNotNull(CompanionDeviceController.class.getMethod("retryWakeWord", String.class)
                .getAnnotation(PostMapping.class));
    }

    @Test
    void delegatesUsingTheAuthenticatedUser() {
        DeviceWakeWordService wakeWordService = mock(DeviceWakeWordService.class);
        DeviceWakeWordVO state = new DeviceWakeWordVO();
        when(wakeWordService.update(7L, "device-1", "小布小布")).thenReturn(state);
        CompanionDeviceController controller = new CompanionDeviceController(
                mock(CompanionDeviceService.class), wakeWordService);
        DeviceWakeWordUpdateDTO dto = new DeviceWakeWordUpdateDTO();
        dto.setWord("小布小布");

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var result = controller.updateWakeWord("device-1", dto);

            assertEquals(state, result.getData());
        }
        verify(wakeWordService).update(7L, "device-1", "小布小布");
    }
}
