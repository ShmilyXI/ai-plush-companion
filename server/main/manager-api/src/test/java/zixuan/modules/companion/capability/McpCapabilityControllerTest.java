package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import zixuan.modules.companion.capability.controller.AdminCapabilityController;
import zixuan.modules.companion.capability.controller.InternalCapabilityController;
import zixuan.modules.companion.capability.dto.McpSyncDTO;
import zixuan.modules.companion.capability.dto.CapabilitySaveDTO;
import zixuan.modules.companion.capability.dto.PluginDefinitionDTO;
import zixuan.modules.companion.capability.entity.McpToolSnapshotEntity;
import zixuan.modules.companion.capability.service.CapabilityRoutePreviewService;
import zixuan.modules.companion.capability.service.CapabilitySecretService;
import zixuan.modules.companion.capability.service.CapabilityService;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.service.InternalCapabilityService;
import zixuan.modules.companion.capability.service.McpCapabilityService;
import zixuan.modules.companion.capability.service.CapabilityRuntimeClient;

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

        try (MockedStatic<zixuan.modules.security.user.SecurityUser> security =
                mockStatic(zixuan.modules.security.user.SecurityUser.class)) {
            security.when(zixuan.modules.security.user.SecurityUser::getUserId).thenReturn(7L);
            assertEquals(List.of(tool), controller.mcpTools("mcp-capability").getData());
            controller.approveMcpTools("mcp-capability",
                    new AdminCapabilityController.McpApprovalRequest(List.of("tool-1")));
        }

        verify(mcp).approve(7L, "mcp-capability", List.of("tool-1"));
    }

    @Test
    void administratorCanListExecutorsTestConnectionsAndSynchronizeTools() throws Exception {
        Method executors = AdminCapabilityController.class.getMethod("pluginExecutors");
        Method test = AdminCapabilityController.class.getMethod("testMcp", String.class);
        Method sync = AdminCapabilityController.class.getMethod("syncMcp", String.class);
        assertNotNull(executors.getAnnotation(GetMapping.class));
        assertNotNull(test.getAnnotation(PostMapping.class));
        assertNotNull(sync.getAnnotation(PostMapping.class));
        assertEquals("sys:role:superAdmin", executors.getAnnotation(RequiresPermissions.class).value()[0]);

        CapabilityRuntimeClient runtime = mock(CapabilityRuntimeClient.class);
        McpCapabilityService mcp = mock(McpCapabilityService.class);
        var executor = new CapabilityRuntimeClient.PluginExecutor(
                "get_weather", "查询天气", java.util.Map.of("type", "object"));
        var operation = new zixuan.modules.companion.capability.vo.McpOperationVO(
                true, null, List.of());
        when(runtime.pluginExecutors()).thenReturn(List.of(executor));
        when(mcp.testConnection(7L, "mcp-capability")).thenReturn(operation);
        when(mcp.syncFromRuntime(7L, "mcp-capability")).thenReturn(operation);
        AdminCapabilityController controller = new AdminCapabilityController(
                mock(CapabilityService.class), mock(CapabilitySecretService.class),
                mock(CapabilityRoutePreviewService.class), mock(DeviceCapabilityService.class), mcp,
                null, null, runtime);

        try (MockedStatic<zixuan.modules.security.user.SecurityUser> security =
                mockStatic(zixuan.modules.security.user.SecurityUser.class)) {
            security.when(zixuan.modules.security.user.SecurityUser::getUserId).thenReturn(7L);
            assertEquals(List.of(executor), controller.pluginExecutors().getData());
            assertEquals(operation, controller.testMcp("mcp-capability").getData());
            assertEquals(operation, controller.syncMcp("mcp-capability").getData());
        }
    }

    @Test
    void pluginCreationAcceptsOnlyRuntimeRegisteredExecutorsAndSchemas() {
        CapabilityRuntimeClient runtime = mock(CapabilityRuntimeClient.class);
        CapabilityService capabilities = mock(CapabilityService.class);
        var executor = new CapabilityRuntimeClient.PluginExecutor(
                "get_weather", "查询天气", java.util.Map.of("type", "object"));
        when(runtime.pluginExecutors()).thenReturn(List.of(executor));
        AdminCapabilityController controller = new AdminCapabilityController(
                capabilities, mock(CapabilitySecretService.class),
                mock(CapabilityRoutePreviewService.class), mock(DeviceCapabilityService.class),
                mock(McpCapabilityService.class), null, null, runtime);

        CapabilitySaveDTO request = new CapabilitySaveDTO();
        request.setType("PLUGIN");
        request.setName("天气");
        PluginDefinitionDTO plugin = new PluginDefinitionDTO();
        plugin.setExecutorName("arbitrary_executor");
        plugin.setInputSchema(java.util.Map.of("type", "object"));
        plugin.setConfigSchema(java.util.Map.of());
        plugin.setSecretFields(List.of());
        plugin.setDefaultConfig(java.util.Map.of());
        request.setPlugin(plugin);

        assertThrows(zixuan.common.exception.RenException.class, () -> controller.create(request));

        plugin.setExecutorName("get_weather");
        try (MockedStatic<zixuan.modules.security.user.SecurityUser> security =
                mockStatic(zixuan.modules.security.user.SecurityUser.class)) {
            security.when(zixuan.modules.security.user.SecurityUser::getUserId).thenReturn(7L);
            controller.create(request);
        }
        verify(capabilities).create(7L, request);
    }
}
