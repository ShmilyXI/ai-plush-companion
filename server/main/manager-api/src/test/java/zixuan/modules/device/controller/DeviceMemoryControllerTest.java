package zixuan.modules.device.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import zixuan.common.redis.RedisUtils;
import zixuan.modules.device.service.CompanionMemoryService;
import zixuan.modules.device.service.DeviceAddressBookService;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.sys.service.SysParamsService;

class DeviceMemoryControllerTest {
    @Test
    void ownerCanRequestCompanionMemoryClear() {
        CompanionMemoryService memoryService = mock(CompanionMemoryService.class);
        DeviceController controller = new DeviceController(
                mock(DeviceService.class),
                mock(DeviceAddressBookService.class),
                mock(RedisUtils.class),
                mock(SysParamsService.class),
                memoryService);

        try (MockedStatic<SecurityUser> securityUser = mockStatic(SecurityUser.class)) {
            securityUser.when(SecurityUser::getUserId).thenReturn(7L);

            controller.clearCompanionMemory("device-id");
        }

        verify(memoryService).clear(7L, 7L, "device-id");
    }
}
