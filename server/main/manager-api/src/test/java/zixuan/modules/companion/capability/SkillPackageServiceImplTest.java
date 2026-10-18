package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import zixuan.modules.companion.capability.dao.SkillPackageDao;
import zixuan.modules.companion.capability.dao.DeviceSkillMappingDao;
import zixuan.modules.companion.capability.dto.SkillPackageDraftDTO;
import zixuan.modules.companion.capability.entity.SkillPackageEntity;
import zixuan.modules.companion.capability.packagefile.LocalSkillPackageStore;
import zixuan.modules.companion.capability.packagefile.SkillPackageBuilder;
import zixuan.modules.companion.capability.packagefile.SkillPackageParser;
import zixuan.modules.companion.capability.packagefile.SkillPackageValidator;
import zixuan.modules.companion.capability.service.impl.SkillPackageServiceImpl;
import zixuan.modules.companion.capability.vo.SkillPackageValidationVO;
import zixuan.common.exception.RenException;

class SkillPackageServiceImplTest {
    @TempDir
    Path root;

    @Test
    void onlineSkillSaveBuildsCanonicalDraftPackage() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        when(packages.insert(any(SkillPackageEntity.class))).thenReturn(1);
        SkillPackageValidationVO valid = new SkillPackageValidationVO();
        valid.setStatus("VALID");
        SkillPackageValidator validator = mock(SkillPackageValidator.class);
        when(validator.validate(any(), anyString())).thenReturn(valid);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, new LocalSkillPackageStore(root),
                new SkillPackageBuilder(), new SkillPackageParser(), validator);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "skill-weather");
        manifest.put("name", "天气");
        manifest.put("version", 1);
        manifest.put("tools", java.util.List.of());
        SkillPackageDraftDTO draft = new SkillPackageDraftDTO();
        draft.setManifest(manifest);
        draft.setSkillMarkdown("查天气");

        var result = service.saveOnlineDraft(7L, "skill-weather", 1, draft);

        assertEquals(1, result.getVersion());
        assertEquals("ONLINE", result.getSource());
    }

    @Test
    void publishMarksDraftImmutable() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        SkillPackageEntity draft = new SkillPackageEntity();
        draft.setCapabilityId("skill-weather");
        draft.setVersionNo(1);
        draft.setValidationStatus("VALID");
        draft.setPublished(0);
        when(packages.selectDraft("skill-weather")).thenReturn(draft);
        when(packages.updateById(draft)).thenReturn(1);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, mock(), new SkillPackageBuilder(),
                new SkillPackageParser(), mock(SkillPackageValidator.class));

        service.publishDraft(7L, "skill-weather");

        assertEquals(1, draft.getPublished());
        assertThrows(RenException.class, () -> service.publishDraft(7L, "skill-weather"));
    }

    @Test
    void compatibilityPackageWithOnlyMarkdownNeedsCompletion() {
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(mock(SkillPackageDao.class), mock(),
                new SkillPackageBuilder(), new SkillPackageParser(), mock(SkillPackageValidator.class));
        byte[] archive = SkillPackageParserTest.zip(Map.of("SKILL.md", "# Imported Weather\nUse weather.\n"));

        var result = service.inspect(new MockMultipartFile(
                "file", "weather.skill.zip", "application/zip", archive));

        assertEquals("INCOMPLETE", result.getValidation().getStatus());
        assertEquals("Imported Weather", result.getName());
    }

    @Test
    void downloadReturnsTheStoredCanonicalArchive() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        LocalSkillPackageStore store = new LocalSkillPackageStore(root);
        SkillPackageBuilder builder = new SkillPackageBuilder();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "skill-weather");
        manifest.put("name", "Weather");
        manifest.put("version", 1);
        manifest.put("tools", List.of());
        byte[] archive = builder.build(manifest, "# Weather\n", Map.of());
        var document = new SkillPackageParser().parse(archive);
        String key = store.put("skill-weather", 1, document.sha256(), archive);
        SkillPackageEntity row = new SkillPackageEntity();
        row.setCapabilityId("skill-weather");
        row.setVersionNo(1);
        row.setStorageKey(key);
        row.setPackageSha256(document.sha256());
        row.setPackageSize((long) archive.length);
        when(packages.selectByVersion("skill-weather", 1)).thenReturn(row);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, store, builder,
                new SkillPackageParser(), mock(SkillPackageValidator.class));

        org.junit.jupiter.api.Assertions.assertArrayEquals(archive, service.download("skill-weather", 1));
    }

    @Test
    void listsPackageVersionsWithValidationMetadata() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        SkillPackageEntity row = new SkillPackageEntity();
        row.setId("package-1");
        row.setCapabilityId("skill-weather");
        row.setVersionNo(1);
        row.setPackageSha256("a".repeat(64));
        row.setPackageSize(10L);
        row.setSourceType("UPLOAD");
        row.setValidationStatus("VALID");
        row.setValidationReportJson("{\"status\":\"VALID\",\"issues\":[]}");
        row.setPublished(1);
        when(packages.selectByCapabilityId("skill-weather")).thenReturn(List.of(row));
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, mock(), new SkillPackageBuilder(),
                new SkillPackageParser(), mock(SkillPackageValidator.class));

        var result = service.list("skill-weather");

        assertEquals(1, result.size());
        assertEquals(1, result.getFirst().getVersion());
        assertEquals("UPLOAD", result.getFirst().getSource());
        assertEquals(true, result.getFirst().getPublished());
    }

    @Test
    void importsLegacyVersionAsPublishedPackageWithSameVersionAndOverrides() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        LocalSkillPackageStore store = new LocalSkillPackageStore(root);
        SkillPackageValidationVO validation = new SkillPackageValidationVO();
        validation.setStatus("VALID");
        SkillPackageValidator validator = mock(SkillPackageValidator.class);
        when(validator.validate(any(), org.mockito.ArgumentMatchers.eq("skill-weather"))).thenReturn(validation);
        when(packages.selectByVersion("skill-weather", 3)).thenReturn(null);
        when(packages.insert(any(SkillPackageEntity.class))).thenReturn(1);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, store, new SkillPackageBuilder(),
                new SkillPackageParser(), validator);

        SkillPackageEntity result = service.importLegacyPublished(0L, "skill-weather", 3,
                "{\"id\":\"skill-weather\",\"name\":\"天气\",\"description\":\"天气查询\","
                        + "\"executionPrompt\":\"调用天气工具\",\"responseMode\":\"LLM\","
                        + "\"timeoutMs\":30000,\"semanticThreshold\":0.7,\"triggers\":[],"
                        + "\"overridableFields\":[\"location\"],\"tools\":[{\"toolType\":\"PLUGIN\","
                        + "\"toolRefId\":\"plugin-weather\",\"toolName\":\"get_weather\","
                        + "\"required\":true,\"defaultParams\":{\"location\":\"杭州\"}}]}",
                7L, new java.util.Date(3000L));

        assertEquals(3, result.getVersionNo());
        assertEquals(1, result.getPublished());
        assertEquals("MIGRATION", result.getSourceType());
        var parsed = new SkillPackageParser().parse(store.get(result.getStorageKey()));
        assertEquals(List.of("location"), parsed.manifest().get("overridableFields"));
        assertEquals(3, parsed.manifest().get("version"));
        verify(packages).insert(any(SkillPackageEntity.class));
    }

    @Test
    void refusesToDeletePublishedPackageUsedByAFixedDeviceBinding() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        SkillPackageEntity published = new SkillPackageEntity();
        published.setId("package-1");
        published.setCapabilityId("skill-weather");
        published.setVersionNo(1);
        published.setPublished(1);
        published.setStorageKey("skill-weather/1/package.skill.zip");
        when(packages.selectByVersion("skill-weather", 1)).thenReturn(published);
        DeviceSkillMappingDao mappings = mock(DeviceSkillMappingDao.class);
        when(mappings.countFixedReferences("skill-weather", 1)).thenReturn(1L);
        var store = mock(zixuan.modules.companion.capability.packagefile.SkillPackageStore.class);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, store,
                new SkillPackageBuilder(), new SkillPackageParser(), mock(SkillPackageValidator.class));
        service.setMappingDao(mappings);

        assertThrows(RenException.class, () -> service.deleteVersion(7L, "skill-weather", 1));

        verify(packages, org.mockito.Mockito.never()).deleteById((java.io.Serializable) any());
        verify(store, org.mockito.Mockito.never()).delete(anyString());
    }

}
