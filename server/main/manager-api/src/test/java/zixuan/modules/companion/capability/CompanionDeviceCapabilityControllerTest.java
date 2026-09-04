package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import zixuan.modules.companion.capability.dto.DeviceSkillBindingDTO;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.controller.CompanionDeviceController;
import zixuan.modules.companion.service.CompanionDeviceService;
import zixuan.modules.companion.wakeword.service.DeviceWakeWordService;
import zixuan.modules.security.user.SecurityUser;

class CompanionDeviceCapabilityControllerTest {

    @Test
    void exposesOwnerScopedSkillRoutes() throws Exception {
        assertNotNull(CompanionDeviceController.class.getMethod("skills", String.class).getAnnotation(GetMapping.class));
        assertNotNull(CompanionDeviceController.class.getMethod("skillCatalog", String.class).getAnnotation(GetMapping.class));
        assertNotNull(CompanionDeviceController.class.getMethod("saveSkills", String.class, List.class)
                .getAnnotation(PutMapping.class));
    }

    @Test
    void delegatesUsingAuthenticatedOwnerWithoutRoleIdentifiers() {
        DeviceCapabilityService capabilities = mock(DeviceCapabilityService.class);
        CompanionDeviceController controller = new CompanionDeviceController(
                mock(CompanionDeviceService.class), mock(DeviceWakeWordService.class), capabilities);
        List<DeviceSkillBindingDTO> request = List.of();

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            controller.saveSkills("device-1", request);
            controller.skillCatalog("device-1");
        }

        verify(capabilities).save(7L, "device-1", request, false);
        verify(capabilities).catalog(7L, "device-1", false);
        assertEquals(0, request.size());
    }
}
