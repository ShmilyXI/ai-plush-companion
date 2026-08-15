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
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
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

    private void published(String skillId, int publishedVersion, String location) {
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId(skillId);
        capability.setType("SKILL");
        capability.setName("天气查询");
        capability.setStatus("PUBLISHED");
        capability.setPublishedVersion(publishedVersion);
        when(capabilities.selectById(skillId)).thenReturn(capability);
        when(versions.selectVersion(skillId, publishedVersion)).thenReturn(version(skillId, publishedVersion, location));
    }

    private CapabilityVersionEntity version(String skillId, int number, String location) {
        CapabilityVersionEntity version = new CapabilityVersionEntity();
        version.setCapabilityId(skillId);
        version.setVersionNo(number);
        version.setContentJson("{\"id\":\"" + skillId + "\",\"name\":\"天气查询\","
                + "\"executionPrompt\":\"查询天气\",\"semanticThreshold\":0.7,\"responseMode\":\"LLM\","
                + "\"timeoutMs\":10000,\"triggers\":[{\"type\":\"KEYWORD\",\"value\":\"天气\"}],"
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
