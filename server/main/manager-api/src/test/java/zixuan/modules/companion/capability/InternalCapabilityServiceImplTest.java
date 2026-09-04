package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.common.exception.RenException;
import zixuan.modules.companion.capability.dao.CapabilitySecretDao;
import zixuan.modules.companion.capability.dao.DeviceToolSnapshotDao;
import zixuan.modules.companion.capability.dao.McpServerDao;
import zixuan.modules.companion.capability.dao.McpToolSnapshotDao;
import zixuan.modules.companion.capability.dto.DeviceToolSnapshotSaveDTO;
import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;
import zixuan.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import zixuan.modules.companion.capability.entity.McpServerEntity;
import zixuan.modules.companion.capability.entity.McpToolSnapshotEntity;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.service.impl.InternalCapabilityServiceImpl;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveToolVO;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;

class InternalCapabilityServiceImplTest {
    private final DeviceDao devices = mock(DeviceDao.class);
    private final DeviceToolSnapshotDao snapshots = mock(DeviceToolSnapshotDao.class);
    private final CapabilitySecretDao secrets = mock(CapabilitySecretDao.class);
    private final McpToolSnapshotDao mcpTools = mock(McpToolSnapshotDao.class);
    private final McpServerDao mcpServers = mock(McpServerDao.class);
    private final DeviceCapabilityService capabilities = mock(DeviceCapabilityService.class);
    private final CompanionModelSecretService cipher = mock(CompanionModelSecretService.class);
    private final InternalCapabilityServiceImpl service = new InternalCapabilityServiceImpl(
            devices, snapshots, secrets, mcpTools, mcpServers, capabilities, cipher);

    @BeforeEach
    void setup() {
        DeviceEntity device = new DeviceEntity();
        device.setId("device-1");
        when(devices.selectById("device-1")).thenReturn(device);
    }

    @Test
    void returnsTheEffectiveBundleWithoutAddingSecrets() {
        EffectiveCapabilityBundleVO bundle = new EffectiveCapabilityBundleVO();
        bundle.setDeviceId("device-1");
        when(capabilities.effectiveBundle("device-1")).thenReturn(bundle);

        assertEquals(bundle, service.bundle("device-1"));
    }

    @Test
    void upsertsSanitizedDeviceToolSnapshots() {
        when(snapshots.insert(any(DeviceToolSnapshotEntity.class))).thenReturn(1);
        DeviceToolSnapshotSaveDTO request = request("self.audio_speaker.set_volume");

        service.saveDeviceTools("device-1", request);

        ArgumentCaptor<DeviceToolSnapshotEntity> inserted = ArgumentCaptor.forClass(DeviceToolSnapshotEntity.class);
        verify(snapshots).insert(inserted.capture());
        DeviceToolSnapshotEntity row = inserted.getValue();
        assertEquals("device-1", row.getDeviceId());
        assertEquals("self.audio_speaker.set_volume", row.getToolName());
        assertEquals("{\"type\":\"object\"}", row.getInputSchemaJson());
        assertEquals(64, row.getSchemaSha256().length());
        assertEquals("esp32-s3", row.getDeviceModel());
        assertEquals("1.2.3", row.getFirmwareVersion());
        assertEquals(1, row.getAvailable());
        assertNotNull(row.getLastSeenAt());
    }

    @Test
    void updatesAnExistingDeviceToolSnapshot() {
        DeviceToolSnapshotEntity existing = new DeviceToolSnapshotEntity();
        existing.setId("snapshot-1");
        existing.setDeviceId("device-1");
        existing.setToolName("self.audio_speaker.set_volume");
        when(snapshots.selectByDeviceAndTool("device-1", "self.audio_speaker.set_volume"))
                .thenReturn(existing);
        when(snapshots.updateById(existing)).thenReturn(1);

        service.saveDeviceTools("device-1", request("self.audio_speaker.set_volume"));

        verify(snapshots).updateById(existing);
        assertEquals("1.2.3", existing.getFirmwareVersion());
    }

    @Test
    void decryptsOnlySecretsReferencedByAnEnabledPluginTool() {
        CapabilitySecretEntity secret = secret("secret-weather", "plugin-weather");
        when(secrets.selectById("secret-weather")).thenReturn(secret);
        when(capabilities.effectiveBundle("device-1")).thenReturn(bundle(tool("PLUGIN", "plugin-weather")));
        when(cipher.decrypt("encrypted-weather-key")).thenReturn("weather-key");

        assertEquals("weather-key", service.secret("device-1", "secret-weather"));
    }

    @Test
    void resolvesMcpToolOwnershipBeforeDecryptingItsSecret() {
        CapabilitySecretEntity secret = secret("secret-mcp", "mcp-capability");
        McpToolSnapshotEntity tool = new McpToolSnapshotEntity();
        tool.setId("mcp-tool-1");
        tool.setMcpServerId("mcp-server-1");
        McpServerEntity server = new McpServerEntity();
        server.setId("mcp-server-1");
        server.setCapabilityId("mcp-capability");
        when(secrets.selectById("secret-mcp")).thenReturn(secret);
        when(capabilities.effectiveBundle("device-1")).thenReturn(bundle(tool("MCP", "mcp-tool-1")));
        when(mcpTools.selectById("mcp-tool-1")).thenReturn(tool);
        when(mcpServers.selectById("mcp-server-1")).thenReturn(server);
        when(cipher.decrypt("encrypted-weather-key")).thenReturn("mcp-token");

        assertEquals("mcp-token", service.secret("device-1", "secret-mcp"));
    }

    @Test
    void rejectsSecretsOutsideTheRequestingDeviceBundle() {
        when(secrets.selectById("secret-weather")).thenReturn(secret("secret-weather", "plugin-weather"));
        when(capabilities.effectiveBundle("device-1")).thenReturn(bundle(tool("PLUGIN", "plugin-news")));

        assertThrows(RenException.class, () -> service.secret("device-1", "secret-weather"));
        verify(cipher, never()).decrypt(any());
    }

    private DeviceToolSnapshotSaveDTO request(String toolName) {
        DeviceToolSnapshotSaveDTO.ToolDTO tool = new DeviceToolSnapshotSaveDTO.ToolDTO();
        tool.setName(toolName);
        tool.setInputSchema(Map.of("type", "object"));
        tool.setAvailable(true);
        DeviceToolSnapshotSaveDTO dto = new DeviceToolSnapshotSaveDTO();
        dto.setDeviceModel("esp32-s3");
        dto.setFirmwareVersion("1.2.3");
        dto.setTools(List.of(tool));
        return dto;
    }

    private CapabilitySecretEntity secret(String id, String capabilityId) {
        CapabilitySecretEntity secret = new CapabilitySecretEntity();
        secret.setId(id);
        secret.setCapabilityId(capabilityId);
        secret.setSecretCiphertext("encrypted-weather-key");
        return secret;
    }

    private EffectiveCapabilityBundleVO bundle(EffectiveToolVO... tools) {
        EffectiveCapabilityBundleVO bundle = new EffectiveCapabilityBundleVO();
        bundle.setDeviceId("device-1");
        java.util.LinkedHashMap<String, EffectiveToolVO> values = new java.util.LinkedHashMap<>();
        for (EffectiveToolVO tool : tools) values.put(tool.getName(), tool);
        bundle.setTools(values);
        return bundle;
    }

    private EffectiveToolVO tool(String type, String refId) {
        EffectiveToolVO tool = new EffectiveToolVO();
        tool.setName(type.toLowerCase() + "-tool");
        tool.setType(type);
        tool.setRefId(refId);
        return tool;
    }
}
