package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dto.McpSyncDTO;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.service.impl.McpCapabilityServiceImpl;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class McpCapabilityServiceImplTest {
    private final McpServerDao servers = mock(McpServerDao.class);
    private final McpToolSnapshotDao tools = mock(McpToolSnapshotDao.class);
    private final DeviceSkillMappingDao mappings = mock(DeviceSkillMappingDao.class);
    private final DeviceDao devices = mock(DeviceDao.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final McpCapabilityServiceImpl service = new McpCapabilityServiceImpl(
            servers, tools, mappings, devices, audit);

    @Test
    void syncPreservesMatchingApprovalAndDisablesNewChangedAndMissingTools() {
        McpServerEntity server = server();
        McpToolSnapshotEntity stable = tool("stable-id", "search", schema("query"), 1, "ACTIVE");
        McpToolSnapshotEntity changed = tool("changed-id", "read", schema("path"), 1, "ACTIVE");
        McpToolSnapshotEntity missing = tool("missing-id", "write", schema("path"), 1, "ACTIVE");
        DeviceEntity device = new DeviceEntity();
        device.setId("device-1");
        device.setCapabilityConfigVersion(8L);
        when(servers.selectById("server-1")).thenReturn(server);
        when(tools.selectByMcpServerId("server-1")).thenReturn(List.of(stable, changed, missing));
        when(tools.insert(any(McpToolSnapshotEntity.class))).thenReturn(1);
        when(tools.updateById(any(McpToolSnapshotEntity.class))).thenReturn(1);
        when(servers.updateById(any(McpServerEntity.class))).thenReturn(1);
        when(mappings.selectEnabledDeviceIds()).thenReturn(List.of("device-1"));
        when(devices.selectByIdForUpdate("device-1")).thenReturn(device);
        when(devices.updateById(device)).thenReturn(1);

        McpSyncDTO request = sync("HEALTHY", List.of(
                discovered("search", schema("query")),
                discovered("read", schema("uri")),
                discovered("new_tool", schema("value"))));
        List<McpToolSnapshotEntity> result = service.sync("server-1", request);

        assertEquals("HEALTHY", server.getHealthStatus());
        assertEquals(1, stable.getApproved());
        assertEquals("ACTIVE", stable.getStatus());
        assertEquals(0, changed.getApproved());
        assertEquals("DRIFTED", changed.getStatus());
        assertEquals(0, missing.getApproved());
        assertEquals("MISSING", missing.getStatus());
        assertEquals(9L, device.getCapabilityConfigVersion());
        McpToolSnapshotEntity created = result.stream()
                .filter(item -> "new_tool".equals(item.getToolName())).findFirst().orElseThrow();
        assertEquals(0, created.getApproved());
        assertEquals("DISCOVERED", created.getStatus());
    }

    @Test
    void failedConnectionUpdatesHealthWithoutRevokingExistingApprovals() {
        McpServerEntity server = server();
        McpToolSnapshotEntity stable = tool("stable-id", "search", schema("query"), 1, "ACTIVE");
        when(servers.selectById("server-1")).thenReturn(server);
        when(tools.selectByMcpServerId("server-1")).thenReturn(List.of(stable));
        when(servers.updateById(any(McpServerEntity.class))).thenReturn(1);

        service.sync("server-1", sync("UNHEALTHY", List.of()));

        assertEquals("UNHEALTHY", server.getHealthStatus());
        assertEquals("RuntimeError", server.getLastError());
        assertEquals(1, stable.getApproved());
        assertEquals("ACTIVE", stable.getStatus());
        verify(tools, never()).updateById(any(McpToolSnapshotEntity.class));
    }

    @Test
    void administratorApprovalIsExplicitAndAudited() {
        McpServerEntity server = server();
        McpToolSnapshotEntity discovered = tool("new-id", "new_tool", schema("value"), 0, "DISCOVERED");
        DeviceEntity device = new DeviceEntity();
        device.setId("device-1");
        device.setCapabilityConfigVersion(4L);
        when(servers.selectByCapabilityId("mcp-capability")).thenReturn(server);
        when(tools.selectByMcpServerId("server-1")).thenReturn(List.of(discovered));
        when(tools.updateById(any(McpToolSnapshotEntity.class))).thenReturn(1);
        when(mappings.selectEnabledDeviceIds()).thenReturn(List.of("device-1"));
        when(devices.selectByIdForUpdate("device-1")).thenReturn(device);
        when(devices.updateById(device)).thenReturn(1);

        List<McpToolSnapshotEntity> result = service.approve(7L, "mcp-capability", List.of("new-id"));

        assertTrue(result.getFirst().getApproved() == 1);
        assertEquals("ACTIVE", result.getFirst().getStatus());
        assertEquals(5L, device.getCapabilityConfigVersion());
        verify(audit).record(7L, null, "mcp.tools.approve", "capability", "mcp-capability",
                Map.of("approvedToolCount", 1));
    }

    @Test
    void administratorCannotApproveDriftedOrMissingTools() {
        McpServerEntity server = server();
        McpToolSnapshotEntity drifted = tool("drifted-id", "read", schema("uri"), 0, "DRIFTED");
        McpToolSnapshotEntity missing = tool("missing-id", "write", schema("path"), 0, "MISSING");
        when(servers.selectByCapabilityId("mcp-capability")).thenReturn(server);
        when(tools.selectByMcpServerId("server-1")).thenReturn(List.of(drifted, missing));
        when(tools.updateById(any(McpToolSnapshotEntity.class))).thenReturn(1);

        assertThrows(RenException.class,
                () -> service.approve(7L, "mcp-capability", List.of("drifted-id")));
        assertThrows(RenException.class,
                () -> service.approve(7L, "mcp-capability", List.of("missing-id")));
    }

    @Test
    void listingDoesNotExposeConnectionSecrets() {
        McpServerEntity server = server();
        McpToolSnapshotEntity tool = tool("stable-id", "search", schema("query"), 1, "ACTIVE");
        when(servers.selectByCapabilityId("mcp-capability")).thenReturn(server);
        when(tools.selectByMcpServerId("server-1")).thenReturn(List.of(tool));

        List<McpToolSnapshotEntity> result = service.list("mcp-capability");

        assertEquals(List.of(tool), result);
        assertFalse(result.toString().contains("secret"));
    }

    private McpServerEntity server() {
        McpServerEntity server = new McpServerEntity();
        server.setId("server-1");
        server.setCapabilityId("mcp-capability");
        server.setHealthStatus("UNKNOWN");
        return server;
    }

    private McpToolSnapshotEntity tool(String id, String name, Map<String, Object> schema,
            int approved, String status) {
        McpToolSnapshotEntity tool = new McpToolSnapshotEntity();
        tool.setId(id);
        tool.setMcpServerId("server-1");
        tool.setToolName(name);
        tool.setInputSchemaJson(xiaozhi.common.utils.JsonUtils.toJsonString(schema));
        tool.setSchemaSha256(hash(schema));
        tool.setApproved(approved);
        tool.setStatus(status);
        tool.setSyncedAt(new Date());
        return tool;
    }

    private McpSyncDTO sync(String health, List<McpSyncDTO.ToolDTO> values) {
        McpSyncDTO dto = new McpSyncDTO();
        dto.setHealthStatus(health);
        dto.setErrorClass("UNHEALTHY".equals(health) ? "RuntimeError" : null);
        dto.setTools(values);
        return dto;
    }

    private McpSyncDTO.ToolDTO discovered(String name, Map<String, Object> schema) {
        McpSyncDTO.ToolDTO tool = new McpSyncDTO.ToolDTO();
        tool.setName(name);
        tool.setInputSchema(schema);
        return tool;
    }

    private Map<String, Object> schema(String field) {
        return Map.of("type", "object", "properties", Map.of(field, Map.of("type", "string")));
    }

    private String hash(Map<String, Object> schema) {
        return McpCapabilityServiceImpl.schemaHash(schema);
    }
}
