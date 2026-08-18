package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.packagefile.LocalSkillPackageStore;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageBuilder;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageParser;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageValidator;
import xiaozhi.modules.companion.capability.service.impl.DeviceCapabilityServiceImpl;
import xiaozhi.modules.companion.capability.service.impl.SkillPackageServiceImpl;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class SkillPackageRoundTripIntegrationTest {
    @TempDir
    Path root;

    @Test
    void uploadPublishDownloadAndReimportPreserveCanonicalPackage() {
        var rows = new HashMap<Integer, SkillPackageEntity>();
        var packages = mock(xiaozhi.modules.companion.capability.dao.SkillPackageDao.class);
        when(packages.insert(any(SkillPackageEntity.class))).thenAnswer(invocation -> {
            SkillPackageEntity row = invocation.getArgument(0);
            rows.put(row.getVersionNo(), row);
            return 1;
        });
        when(packages.selectDraft("skill-weather")).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> Integer.valueOf(0).equals(row.getPublished()))
                .max(java.util.Comparator.comparing(SkillPackageEntity::getVersionNo)).orElse(null));
        when(packages.selectByVersion(any(String.class), any(Integer.class)))
                .thenAnswer(invocation -> rows.get(invocation.getArgument(1)));
        when(packages.updateById(any(SkillPackageEntity.class))).thenReturn(1);

        var packageService = new SkillPackageServiceImpl(packages, new LocalSkillPackageStore(root),
                new SkillPackageBuilder(), new SkillPackageParser(), validValidator());
        byte[] versionOne = archive(1, "先确认地点，再查询天气。", "杭州");
        byte[] versionTwo = archive(2, "使用最新天气数据回答。", "上海");

        packageService.saveUploadedDraft(7L, "skill-weather", file(versionOne));
        packageService.publishDraft(7L, "skill-weather");
        byte[] exportedOne = packageService.download("skill-weather", 1);
        var importedOne = packageService.inspect(file(exportedOne));
        assertArrayEquals(versionOne, exportedOne);
        assertEquals(importedOne.getPackageSha256(), rows.get(1).getPackageSha256());
        assertEquals("VALID", importedOne.getValidation().getStatus());

        packageService.saveUploadedDraft(7L, "skill-weather", file(versionTwo));
        packageService.publishDraft(7L, "skill-weather");
        assertArrayEquals(versionTwo, packageService.download("skill-weather", 2));
        assertEquals(1, rows.get(1).getPublished());
        assertEquals(1, rows.get(2).getPublished());
    }

    @Test
    void deviceBindingsFollowLatestOrRemainFixedAndStayDeviceLocal() {
        var devices = mock(DeviceDao.class);
        var mappings = mock(DeviceSkillMappingDao.class);
        var capabilities = mock(CapabilityDao.class);
        var versions = mock(CapabilityVersionDao.class);
        var deviceOne = device("device-one", 7L);
        var deviceTwo = device("device-two", 7L);
        var deviceOther = device("device-other", 8L);
        when(devices.selectOwnedByIdForUpdate("device-one", 7L)).thenReturn(deviceOne);
        when(devices.selectOwnedByIdForUpdate("device-two", 7L)).thenReturn(deviceTwo);
        when(devices.updateById(any(DeviceEntity.class))).thenReturn(1);
        when(mappings.insert(any(DeviceSkillMappingEntity.class))).thenReturn(1);

        var skill = skill(1);
        when(capabilities.selectById("skill-weather")).thenReturn(skill);
        when(versions.selectVersion("skill-weather", 1)).thenReturn(version(1, "杭州"));
        when(versions.selectVersion("skill-weather", 2)).thenReturn(version(2, "上海"));

        var service = new DeviceCapabilityServiceImpl(devices, mappings, capabilities, versions,
                mock(CompanionAuditService.class));
        var latest = binding("LATEST", null);
        var fixed = binding("FIXED", 1);
        service.save(7L, "device-one", List.of(latest), false);
        service.save(7L, "device-two", List.of(fixed), false);

        DeviceSkillMappingEntity latestRow = mapping("device-one", "LATEST", null);
        DeviceSkillMappingEntity fixedRow = mapping("device-two", "FIXED", 1);
        when(devices.selectById("device-one")).thenReturn(deviceOne);
        when(devices.selectById("device-two")).thenReturn(deviceTwo);
        when(devices.selectById("device-other")).thenReturn(deviceOther);
        when(mappings.selectEnabledByDevice("device-one")).thenReturn(List.of(latestRow));
        when(mappings.selectEnabledByDevice("device-two")).thenReturn(List.of(fixedRow));
        when(mappings.selectEnabledByDevice("device-other")).thenReturn(List.of());

        skill.setPublishedVersion(2);
        assertEquals(2, service.effectiveBundle("device-one").getSkills().get(0).getVersion());
        assertEquals(1, service.effectiveBundle("device-two").getSkills().get(0).getVersion());
        assertEquals(List.of(), service.effectiveBundle("device-other").getSkills());
    }

    private SkillPackageValidator validValidator() {
        return new SkillPackageValidator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
    }

    private byte[] archive(int version, String prompt, String location) {
        return new SkillPackageBuilder().build(Map.of(
                "schemaVersion", 1,
                "id", "skill-weather",
                "name", "天气查询",
                "version", version,
                "runtime", Map.of("responseMode", "LLM", "timeoutMs", 30000, "semanticThreshold", 0.7),
                "triggers", List.of(Map.of("type", "KEYWORD", "value", "天气")),
                "tools", List.of(),
                "description", location), prompt, Map.of());
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "weather.skill.zip", "application/zip", bytes);
    }

    private CapabilityEntity skill(int publishedVersion) {
        var entity = new CapabilityEntity();
        entity.setId("skill-weather");
        entity.setType("SKILL");
        entity.setName("天气查询");
        entity.setStatus("PUBLISHED");
        entity.setPublishedVersion(publishedVersion);
        return entity;
    }

    private CapabilityVersionEntity version(int number, String location) {
        var entity = new CapabilityVersionEntity();
        entity.setCapabilityId("skill-weather");
        entity.setVersionNo(number);
        entity.setContentJson("{\"id\":\"skill-weather\",\"name\":\"天气查询\","
                + "\"executionPrompt\":\"查询" + location + "天气\",\"semanticThreshold\":0.7,"
                + "\"responseMode\":\"LLM\",\"timeoutMs\":30000,\"triggers\":[],\"tools\":[]}");
        return entity;
    }

    private DeviceEntity device(String id, Long userId) {
        var entity = new DeviceEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setCapabilityConfigVersion(0L);
        return entity;
    }

    private xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO binding(String mode, Integer version) {
        var binding = new xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO();
        binding.setSkillId("skill-weather");
        binding.setVersionMode(mode);
        binding.setFixedVersion(version);
        binding.setEnabled(true);
        return binding;
    }

    private DeviceSkillMappingEntity mapping(String deviceId, String mode, Integer version) {
        var row = new DeviceSkillMappingEntity();
        row.setDeviceId(deviceId);
        row.setSkillId("skill-weather");
        row.setVersionMode(mode);
        row.setFixedVersion(version);
        row.setEnabled(1);
        return row;
    }
}
