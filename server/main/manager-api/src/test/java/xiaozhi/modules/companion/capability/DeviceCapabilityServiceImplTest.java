package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.service.impl.DeviceCapabilityServiceImpl;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class DeviceCapabilityServiceImplTest {
    private final DeviceDao devices = mock(DeviceDao.class);
    private final DeviceSkillMappingDao mappings = mock(DeviceSkillMappingDao.class);
    private final CapabilityDao capabilities = mock(CapabilityDao.class);
    private final CapabilityVersionDao versions = mock(CapabilityVersionDao.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final DeviceCapabilityServiceImpl service = new DeviceCapabilityServiceImpl(
            devices, mappings, capabilities, versions, audit);
    private final DeviceEntity device = device();

    @BeforeEach
    void setup() {
        when(devices.selectOwnedByIdForUpdate("device-1", 7L)).thenReturn(device);
        when(devices.selectById("device-1")).thenReturn(device);
        when(devices.updateById(any(DeviceEntity.class))).thenReturn(1);
        when(mappings.insert(any(DeviceSkillMappingEntity.class))).thenReturn(1);
        published("skill-weather", 2, "上海");
    }

    @Test
    void ownerCanBindLatestVersionAndDeviceOverrideWins() {
        DeviceSkillBindingDTO binding = binding("skill-weather", "LATEST", null, Map.of("location", "北京"));

        var saved = service.save(7L, "device-1", List.of(binding), false);

        assertEquals(4L, device.getCapabilityConfigVersion());
        assertEquals(1, saved.size());
        assertEquals(2, saved.get(0).getResolvedVersion());
        ArgumentCaptor<DeviceSkillMappingEntity> inserted = ArgumentCaptor.forClass(DeviceSkillMappingEntity.class);
        verify(mappings).insert(inserted.capture());
        assertEquals(4L, inserted.getValue().getConfigVersion());

        when(mappings.selectEnabledByDevice("device-1")).thenReturn(List.of(inserted.getValue()));
        var bundle = service.effectiveBundle("device-1");
        assertEquals(4L, bundle.getConfigVersion());
        assertEquals("北京", bundle.getSkills().get(0).getDefaults().get("location"));
        assertEquals("北京", bundle.getTools().get("get_weather").getDefaults().get("location"));
    }

    @Test
    void supportsFixedVersionsAndRejectsUnknownOverrideFields() {
        CapabilityVersionEntity versionOne = version("skill-weather", 1, "杭州");
        when(versions.selectVersion("skill-weather", 1)).thenReturn(versionOne);

        var fixed = service.save(7L, "device-1",
                List.of(binding("skill-weather", "FIXED", 1, Map.of("location", "苏州"))), false);
        assertEquals(1, fixed.get(0).getResolvedVersion());

        assertThrows(RenException.class, () -> service.save(7L, "device-1",
                List.of(binding("skill-weather", "LATEST", null, Map.of("api_key", "unsafe"))), false));
    }

    @Test
    void deniesCrossUserButAllowsExplicitSuperAdminAdministration() {
        when(devices.selectOwnedByIdForUpdate("device-1", 8L)).thenReturn(null);
        assertThrows(RenException.class, () -> service.save(8L, "device-1", List.of(), false));

        when(devices.selectByIdForUpdate("device-1")).thenReturn(device);
        service.save(99L, "device-1", List.of(), true);
        assertEquals(4L, device.getCapabilityConfigVersion());
    }

    @Test
    void configurationVersionIsMonotonicAndRoleChangesDoNotAlterBindings() {
        service.save(7L, "device-1", List.of(binding("skill-weather", "LATEST", null, Map.of())), false);
        service.save(7L, "device-1", List.of(), false);
        assertEquals(5L, device.getCapabilityConfigVersion());

        device.setAgentId("role-b");
        when(mappings.selectEnabledByDevice("device-1")).thenReturn(List.of());
        assertEquals(5L, service.effectiveBundle("device-1").getConfigVersion());
    }

    @Test
    void effectiveBundleIncludesApprovedMcpRuntimeWithoutSecretValues() {
        McpToolSnapshotDao mcpTools = mock(McpToolSnapshotDao.class);
        McpServerDao mcpServers = mock(McpServerDao.class);
        service.setMcpRuntimeDaos(mcpTools, mcpServers);
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("skill-mcp");
        capability.setType("SKILL");
        capability.setStatus("PUBLISHED");
        capability.setPublishedVersion(1);
        when(capabilities.selectById("skill-mcp")).thenReturn(capability);
        CapabilityVersionEntity version = new CapabilityVersionEntity();
        version.setCapabilityId("skill-mcp");
        version.setVersionNo(1);
        version.setContentJson("{\"id\":\"skill-mcp\",\"name\":\"MCP 搜索\","
                + "\"executionPrompt\":\"搜索\",\"semanticThreshold\":0.7,\"responseMode\":\"LLM\","
                + "\"timeoutMs\":10000,\"triggers\":[],\"tools\":[{\"toolType\":\"MCP\","
                + "\"toolRefId\":\"snapshot-1\",\"toolName\":\"mcp_search\",\"defaultParams\":{}}]}");
        when(versions.selectVersion("skill-mcp", 1)).thenReturn(version);
        DeviceSkillMappingEntity mapping = new DeviceSkillMappingEntity();
        mapping.setDeviceId("device-1");
        mapping.setSkillId("skill-mcp");
        mapping.setVersionMode("LATEST");
        mapping.setEnabled(1);
        when(mappings.selectEnabledByDevice("device-1")).thenReturn(List.of(mapping));
        McpToolSnapshotEntity snapshot = new McpToolSnapshotEntity();
        snapshot.setId("snapshot-1");
        snapshot.setMcpServerId("server-1");
        snapshot.setToolName("mcp_search");
        snapshot.setInputSchemaJson("{\"type\":\"object\"}");
        snapshot.setSchemaSha256("a".repeat(64));
        snapshot.setApproved(1);
        snapshot.setStatus("ACTIVE");
        when(mcpTools.selectById("snapshot-1")).thenReturn(snapshot);
        McpServerEntity server = new McpServerEntity();
        server.setId("server-1");
        server.setTransport("SSE");
        server.setConnectionConfigJson("{\"url\":\"https://mcp.example.test/sse\",\"headers\":{}}");
        server.setSecretRefsJson("{\"headers.Authorization\":\"secret-auth\"}");
        when(mcpServers.selectById("server-1")).thenReturn(server);

        Map<String, Object> runtime = service.effectiveBundle("device-1")
                .getTools().get("mcp_search").getRuntime();

        assertEquals("server-1", runtime.get("serverId"));
        assertEquals("secret-auth", ((Map<?, ?>) runtime.get("secretRefs")).get("headers.Authorization"));
        assertEquals(false, runtime.toString().contains("runtime-token"));
    }

    @Test
    void listsPublishedSkillsWithVersionsOverridesAndDeviceToolAvailability() {
        DeviceToolSnapshotDao deviceTools = mock(DeviceToolSnapshotDao.class);
        service.setDeviceToolSnapshotDao(deviceTools);
        CapabilityEntity weather = publishedCapability("skill-weather", "天气查询", 2);
        CapabilityEntity brightness = publishedCapability("skill-brightness", "亮度调节", 1);
        when(capabilities.selectList(any())).thenReturn(List.of(weather, brightness));
        when(versions.selectList(any())).thenReturn(List.of(
                version("skill-weather", 1, "杭州"), version("skill-weather", 2, "上海")));

        CapabilityVersionEntity brightnessVersion = new CapabilityVersionEntity();
        brightnessVersion.setCapabilityId("skill-brightness");
        brightnessVersion.setVersionNo(1);
        brightnessVersion.setContentJson("{\"id\":\"skill-brightness\",\"name\":\"亮度调节\","
                + "\"overridableFields\":[\"brightness\"],\"tools\":[{\"toolType\":\"DEVICE_TOOL\","
                + "\"toolName\":\"self.screen.set_brightness\",\"defaultParams\":{\"brightness\":50}}]}");
        when(versions.selectVersion("skill-brightness", 1)).thenReturn(brightnessVersion);
        when(versions.selectVersion("skill-weather", 2)).thenReturn(version("skill-weather", 2, "上海"));

        DeviceToolSnapshotEntity unavailable = new DeviceToolSnapshotEntity();
        unavailable.setAvailable(0);
        when(deviceTools.selectByDeviceAndTool("device-1", "self.screen.set_brightness")).thenReturn(unavailable);

        var catalog = service.catalog(7L, "device-1", false);

        assertEquals(List.of(1, 2), catalog.get(0).getVersions());
        assertEquals(List.of("location"), catalog.get(0).getOverridableFields());
        assertEquals("上海", catalog.get(0).getDefaults().get("location"));
        assertEquals(true, catalog.get(0).isAvailable());
        assertEquals(false, catalog.get(1).isAvailable());
        assertEquals("设备工具 self.screen.set_brightness 当前不可用", catalog.get(1).getUnavailableReason());
    }

    private void published(String skillId, int publishedVersion, String location) {
        CapabilityEntity capability = publishedCapability(skillId, "天气查询", publishedVersion);
        when(capabilities.selectById(skillId)).thenReturn(capability);
        when(versions.selectVersion(skillId, publishedVersion)).thenReturn(version(skillId, publishedVersion, location));
    }

    private CapabilityEntity publishedCapability(String skillId, String name, int publishedVersion) {
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId(skillId);
        capability.setType("SKILL");
        capability.setName(name);
        capability.setStatus("PUBLISHED");
        capability.setPublishedVersion(publishedVersion);
        return capability;
    }

    private CapabilityVersionEntity version(String skillId, int number, String location) {
        CapabilityVersionEntity version = new CapabilityVersionEntity();
        version.setCapabilityId(skillId);
        version.setVersionNo(number);
        version.setContentJson("{\"id\":\"" + skillId + "\",\"name\":\"天气查询\","
                + "\"executionPrompt\":\"查询天气\",\"semanticThreshold\":0.7,\"responseMode\":\"LLM\","
                + "\"timeoutMs\":10000,\"triggers\":[{\"type\":\"KEYWORD\",\"value\":\"天气\"}],"
                + "\"overridableFields\":[\"location\"],"
                + "\"tools\":[{\"toolType\":\"PLUGIN\",\"toolRefId\":\"plugin-weather\","
                + "\"toolName\":\"get_weather\",\"defaultParams\":{\"location\":\"" + location + "\"}}]}");
        return version;
    }

    private DeviceSkillBindingDTO binding(String skillId, String versionMode, Integer fixedVersion,
            Map<String, Object> overrides) {
        DeviceSkillBindingDTO binding = new DeviceSkillBindingDTO();
        binding.setSkillId(skillId);
        binding.setVersionMode(versionMode);
        binding.setFixedVersion(fixedVersion);
        binding.setEnabled(true);
        binding.setOverrides(overrides);
        binding.setTriggerPriority(10);
        return binding;
    }

    private DeviceEntity device() {
        DeviceEntity result = new DeviceEntity();
        result.setId("device-1");
        result.setUserId(7L);
        result.setAgentId("role-a");
        result.setCapabilityConfigVersion(3L);
        return result;
    }
}
