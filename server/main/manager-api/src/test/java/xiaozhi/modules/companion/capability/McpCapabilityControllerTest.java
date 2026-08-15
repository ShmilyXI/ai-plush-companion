package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import xiaozhi.modules.companion.capability.controller.AdminCapabilityController;
import xiaozhi.modules.companion.capability.controller.InternalCapabilityController;
import xiaozhi.modules.companion.capability.dto.McpSyncDTO;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.service.InternalCapabilityService;
import xiaozhi.modules.companion.capability.service.McpCapabilityService;

class McpCapabilityControllerTest {

    @Test
    void internalRuntimeCanReportMcpSyncState() throws Exception {
        Method method = InternalCapabilityController.class.getMethod("syncMcp", String.class, McpSyncDTO.class);
        assertNotNull(method.getAnnotation(PostMapping.class));
        McpCapabilityService mcp = mock(McpCapabilityService.class);
        InternalCapabilityController controller = new InternalCapabilityController(
                mock(InternalCapabilityService.class), mcp);
        McpSyncDTO request = new McpSyncDTO();
        request.setHealthStatus("HEALTHY");
        request.setTools(List.of());

        controller.syncMcp("server-1", request);

        verify(mcp).sync("server-1", request);
    }

    @Test
    void administratorCanListAndReplaceMcpWhitelist() throws Exception {
        Method list = AdminCapabilityController.class.getMethod("mcpTools", String.class);
        Method approve = AdminCapabilityController.class.getMethod(
                "approveMcpTools", String.class, AdminCapabilityController.McpApprovalRequest.class);
        assertNotNull(list.getAnnotation(GetMapping.class));
        assertNotNull(approve.getAnnotation(PutMapping.class));
        assertEquals("sys:role:superAdmin", list.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("sys:role:superAdmin", approve.getAnnotation(RequiresPermissions.class).value()[0]);

        McpCapabilityService mcp = mock(McpCapabilityService.class);
        McpToolSnapshotEntity tool = new McpToolSnapshotEntity();
        tool.setId("tool-1");
        when(mcp.list("mcp-capability")).thenReturn(List.of(tool));
        when(mcp.approve(7L, "mcp-capability", List.of("tool-1"))).thenReturn(List.of(tool));
        AdminCapabilityController controller = new AdminCapabilityController(
                mock(CapabilityService.class), mock(CapabilitySecretService.class),
                mock(CapabilityRoutePreviewService.class), mock(DeviceCapabilityService.class), mcp);

        try (MockedStatic<xiaozhi.modules.security.user.SecurityUser> security =
                mockStatic(xiaozhi.modules.security.user.SecurityUser.class)) {
            security.when(xiaozhi.modules.security.user.SecurityUser::getUserId).thenReturn(7L);
            assertEquals(List.of(tool), controller.mcpTools("mcp-capability").getData());
            controller.approveMcpTools("mcp-capability",
                    new AdminCapabilityController.McpApprovalRequest(List.of("tool-1")));
        }

        verify(mcp).approve(7L, "mcp-capability", List.of("tool-1"));
    }
}
