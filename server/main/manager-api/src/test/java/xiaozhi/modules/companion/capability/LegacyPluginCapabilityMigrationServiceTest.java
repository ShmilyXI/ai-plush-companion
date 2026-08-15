package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.companion.capability.dao.CapabilitySecretDao;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.entity.CapabilitySecretEntity;
import xiaozhi.modules.companion.capability.init.LegacyPluginCapabilityMigrationService;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.vo.DeviceSkillBindingVO;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class LegacyPluginCapabilityMigrationServiceTest {
    private final AgentPluginMappingMapper legacyMappings = mock(AgentPluginMappingMapper.class);
    private final DeviceDao devices = mock(DeviceDao.class);
    private final DeviceCapabilityService deviceCapabilities = mock(DeviceCapabilityService.class);
    private final CapabilitySecretService secretService = mock(CapabilitySecretService.class);
    private final CapabilitySecretDao secretDao = mock(CapabilitySecretDao.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final LegacyPluginCapabilityMigrationService service = new LegacyPluginCapabilityMigrationService(
            legacyMappings, devices, deviceCapabilities, secretService, secretDao, audit);

    @BeforeEach
    void setup() {
        when(deviceCapabilities.list(eq(0L), any(String.class), eq(true))).thenReturn(List.of());
        when(deviceCapabilities.save(eq(0L), any(String.class), any(), eq(true))).thenReturn(List.of());
    }

    @Test
    void migratesWeatherParametersAndPerDeviceSecretReferencesWithoutPlaintextOverrides() {
        AgentPluginMapping mapping = mapping(11L, "agent-1", "get_weather",
                "{\"default_location\":\"杭州\",\"api_host\":\"weather.example\",\"api_key\":\"key-a\"}");
        when(legacyMappings.selectAllWithProviderCode()).thenReturn(List.of(mapping));
        when(devices.selectByAgentId("agent-1")).thenReturn(List.of(device("device-a"), device("device-b")));
        List<CapabilitySecretEntity> stored = new ArrayList<>();
        when(secretDao.selectByCapabilityAndName(any(String.class), any(String.class))).thenAnswer(invocation ->
                stored.stream().filter(secret -> secret.getCapabilityId().equals(invocation.getArgument(0))
                        && secret.getSecretName().equals(invocation.getArgument(1))).findFirst().orElse(null));
        when(secretService.save(eq(0L), eq("plugin-weather"), any(String.class), eq("key-a")))
                .thenAnswer(invocation -> {
                    CapabilitySecretEntity secret = new CapabilitySecretEntity();
                    secret.setId("secret-" + (stored.size() + 1));
                    secret.setCapabilityId("plugin-weather");
                    secret.setSecretName(invocation.getArgument(2));
                    stored.add(secret);
                    return true;
                });

        service.migrate();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceSkillBindingDTO>> bindings = ArgumentCaptor.forClass(List.class);
        verify(deviceCapabilities).save(eq(0L), eq("device-a"), bindings.capture(), eq(true));
        DeviceSkillBindingDTO weather = bindings.getValue().get(0);
        assertEquals("skill-weather", weather.getSkillId());
        assertEquals("杭州", weather.getOverrides().get("location"));
        assertEquals("weather.example", weather.getOverrides().get("api_host"));
        assertEquals("secret-1", weather.getOverrides().get("api_key_secret_id"));
        assertFalse(weather.getOverrides().containsKey("api_key"));
        verify(deviceCapabilities).save(eq(0L), eq("device-b"), any(), eq(true));
    }

    @Test
    void preservesAnExistingDeviceBindingAsTheNewSourceOfTruth() {
        when(legacyMappings.selectAllWithProviderCode()).thenReturn(List.of(
                mapping(12L, "agent-1", "web_search", "{\"provider\":\"tavily\",\"api_key\":\"old\"}")));
        when(devices.selectByAgentId("agent-1")).thenReturn(List.of(device("device-a")));
        DeviceSkillBindingVO existing = new DeviceSkillBindingVO();
        existing.setSkillId("skill-web-search");
        existing.setVersionMode("LATEST");
        existing.setEnabled(true);
        existing.setOverrides(Map.of("provider", "metaso"));
        when(deviceCapabilities.list(0L, "device-a", true)).thenReturn(List.of(existing));

        service.migrate();

        verify(deviceCapabilities, never()).save(eq(0L), eq("device-a"), any(), eq(true));
        verify(secretService, never()).save(any(), any(), any(), any());
    }

    @Test
    void recordsRowsThatCannotYetBeMappedToADevice() {
        AgentPluginMapping mapping = mapping(13L, "agent-without-device", "get_news_from_newsnow", "{}");
        when(legacyMappings.selectAllWithProviderCode()).thenReturn(List.of(mapping));
        when(devices.selectByAgentId("agent-without-device")).thenReturn(List.of());

        service.migrate();

        verify(audit).record(eq(0L), eq(null), eq("capability.migration.unmapped"),
                eq("agentPluginMapping"), eq("13"), any());
    }

    private AgentPluginMapping mapping(Long id, String agentId, String providerCode, String params) {
        AgentPluginMapping mapping = new AgentPluginMapping();
        mapping.setId(id);
        mapping.setAgentId(agentId);
        mapping.setPluginId("legacy-" + providerCode);
        mapping.setProviderCode(providerCode);
        mapping.setParamInfo(params);
        return mapping;
    }

    private DeviceEntity device(String id) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(7L);
        device.setAgentId("agent-1");
        return device;
    }
}
