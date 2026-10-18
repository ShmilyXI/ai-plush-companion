package zixuan.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import zixuan.modules.device.service.CompanionMemoryService;
import zixuan.modules.device.service.CompanionMemoryService.MemoryItem;
import zixuan.modules.security.user.SecurityUser;

class CompanionMemoryControllerTest {
    @Test
    void normalOwnerCanListCorrectDeleteAndClearMemories() {
        CompanionMemoryService service = mock(CompanionMemoryService.class);
        CompanionMemoryController controller = new CompanionMemoryController(service);
        when(service.list(7L, 7L, "device-id"))
                .thenReturn(List.of(new MemoryItem("m1", "内容", "2026-01-01", null, null, null, null)));

        try (MockedStatic<SecurityUser> securityUser = mockStatic(SecurityUser.class)) {
            securityUser.when(SecurityUser::getUserId).thenReturn(7L);

            assertEquals("m1", controller.list("device-id").getData().get(0).id());
            controller.update("device-id", "m1", new CompanionMemoryController.MemoryUpdateRequest("新内容"));
            controller.delete("device-id", "m1");
            controller.clear("device-id");
        }

        verify(service).update(7L, 7L, "device-id", "m1", "新内容");
        verify(service).delete(7L, 7L, "device-id", "m1");
        verify(service).clear(7L, 7L, "device-id");
    }
}
