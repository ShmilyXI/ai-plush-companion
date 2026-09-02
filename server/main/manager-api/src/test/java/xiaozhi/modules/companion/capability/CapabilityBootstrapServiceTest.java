package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.init.CapabilityBootstrapService;
import xiaozhi.modules.companion.capability.init.LegacyPluginCapabilityMigrationService;
import xiaozhi.modules.companion.capability.init.LegacySkillPackageMigrationService;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.SkillPackageService;

class CapabilityBootstrapServiceTest {
    private final CapabilityDao capabilityDao = mock(CapabilityDao.class);
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private final SkillPackageService skillPackages = mock(SkillPackageService.class);
    private final Map<String, CapabilityEntity> stored = new LinkedHashMap<>();
    private final Set<String> publishedPackages = new HashSet<>();
    private final CapabilityBootstrapService service = new CapabilityBootstrapService(capabilityDao, capabilities);

    @BeforeEach
    void setup() {
        service.setSkillPackageService(skillPackages);
        when(capabilityDao.selectById(any(String.class))).thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
        when(skillPackages.selectVersion(any(String.class), any(Integer.class))).thenAnswer(invocation -> {
            String capabilityId = invocation.getArgument(0);
            if (!publishedPackages.contains(capabilityId)) return null;
            SkillPackageEntity packageRow = new SkillPackageEntity();
            packageRow.setCapabilityId(capabilityId);
            packageRow.setVersionNo(invocation.getArgument(1));
            packageRow.setPublished(1);
            return packageRow;
        });
        when(capabilityDao.insert(any(CapabilityEntity.class))).thenAnswer(invocation -> {
            CapabilityEntity entity = invocation.getArgument(0);
            stored.put(entity.getId(), entity);
            return 1;
        });
        when(capabilities.publish(eq(0L), any(String.class))).thenAnswer(invocation -> {
            CapabilityEntity entity = stored.get(invocation.getArgument(1));
            entity.setStatus("PUBLISHED");
            entity.setPublishedVersion(1);
            if ("SKILL".equals(entity.getType())) publishedPackages.add(entity.getId());
            return null;
        });
    }

    @Test
    void republishesOfficialSkillWhenPublishedPackageIsMissing() {
        CapabilityEntity existing = new CapabilityEntity();
        existing.setId("skill-weather");
        existing.setType("SKILL");
        existing.setStatus("PUBLISHED");
        existing.setPublishedVersion(2);
        stored.put(existing.getId(), existing);

        service.initialize();

        verify(capabilities).update(eq(0L), eq("skill-weather"), any(CapabilitySaveDTO.class));
        verify(capabilities).publish(0L, "skill-weather");
    }

    @Test
    void repeatedBootstrapCreatesOnePublishedPluginAndSkillForEachOfficialTool() {
        LegacyPluginCapabilityMigrationService migration = mock(LegacyPluginCapabilityMigrationService.class);
        LegacySkillPackageMigrationService skillMigration = mock(LegacySkillPackageMigrationService.class);
        service.setLegacyMigration(migration);
        service.setLegacySkillPackageMigration(skillMigration);
        service.initialize();
        service.initialize();

        assertEquals(List.of(
                "plugin-weather", "plugin-news", "plugin-web-search",
                "skill-weather", "skill-news", "skill-web-search"),
                stored.keySet().stream().toList());
        assertTrue(stored.values().stream().allMatch(value -> "PUBLISHED".equals(value.getStatus())));
        verify(capabilityDao, times(6)).insert(any(CapabilityEntity.class));
        verify(capabilities, times(6)).publish(eq(0L), any(String.class));
        verify(migration, times(2)).migrate();
        verify(skillMigration, times(2)).migrate();
    }

    @Test
    void officialSkillsUseMixedChineseTriggersAndExactlyOneExistingPluginTool() {
        service.initialize();

        ArgumentCaptor<CapabilitySaveDTO> drafts = ArgumentCaptor.forClass(CapabilitySaveDTO.class);
        verify(capabilities, times(6)).update(eq(0L), any(String.class), drafts.capture());
        CapabilitySaveDTO newsPlugin = drafts.getAllValues().stream()
                .filter(value -> "PLUGIN".equals(value.getType()))
                .filter(value -> "get_news_from_newsnow".equals(value.getPlugin().getExecutorName()))
                .findFirst().orElseThrow();
        List<CapabilitySaveDTO> skills = drafts.getAllValues().stream()
                .filter(value -> "SKILL".equals(value.getType())).toList();

        assertEquals(3, skills.size());
        for (CapabilitySaveDTO skill : skills) {
            assertFalse(skill.getExecutionPrompt().isBlank());
            assertEquals(1, skill.getTools().size());
            assertEquals("PLUGIN", skill.getTools().get(0).getToolType());
            assertTrue(skill.getTriggers().stream().anyMatch(trigger -> "KEYWORD".equals(trigger.getType())));
            assertTrue(skill.getTriggers().stream().anyMatch(trigger -> "POSITIVE_EXAMPLE".equals(trigger.getType())));
            assertTrue(skill.getTriggers().stream().anyMatch(trigger -> "NEGATIVE_EXAMPLE".equals(trigger.getType())));
        }
        assertEquals("get_weather", skills.get(0).getTools().get(0).getToolName());
        assertTrue(skills.get(0).getExecutionPrompt().contains("设备默认地区"));
        assertTrue(skills.get(0).getExecutionPrompt().contains("直接调用"));
        assertEquals("get_news_from_newsnow", skills.get(1).getTools().get(0).getToolName());
        assertEquals("web_search", skills.get(2).getTools().get(0).getToolName());
        assertEquals(List.of("location"),
                skills.get(0).getTools().get(0).getDefaultParams().keySet().stream().sorted().toList());
        assertEquals(List.of("source"),
                skills.get(1).getTools().get(0).getDefaultParams().keySet().stream().sorted().toList());
        Map<?, ?> newsInputProperties = (Map<?, ?>) newsPlugin.getPlugin().getInputSchema().get("properties");
        assertEquals(List.of("detail", "lang", "source"), newsInputProperties.keySet().stream()
                .map(String::valueOf).sorted().toList());
        assertEquals(List.of("news_sources", "url"), newsPlugin.getPlugin().getConfigSchema().keySet().stream()
                .map(String::valueOf).sorted().toList());
        assertEquals(List.of(),
                skills.get(2).getTools().get(0).getDefaultParams().keySet().stream().sorted().toList());
        assertEquals(100, skills.get(2).getTriggers().stream()
                .filter(trigger -> "搜索".equals(trigger.getValue()))
                .findFirst().orElseThrow().getPriority());
        assertEquals(50, skills.get(2).getTriggers().stream()
                .filter(trigger -> "查一下".equals(trigger.getValue()))
                .findFirst().orElseThrow().getPriority());
    }

    @Test
    void weatherPluginAllowsOpenMeteoFallbackWithoutAnExternalSecret() {
        service.initialize();

        ArgumentCaptor<CapabilitySaveDTO> drafts = ArgumentCaptor.forClass(CapabilitySaveDTO.class);
        verify(capabilities, times(6)).update(eq(0L), any(String.class), drafts.capture());
        CapabilitySaveDTO weather = drafts.getAllValues().stream()
                .filter(value -> "PLUGIN".equals(value.getType()))
                .filter(value -> "get_weather".equals(value.getPlugin().getExecutorName()))
                .findFirst().orElseThrow();

        assertEquals(List.of(), weather.getPlugin().getSecretFields());
        assertFalse(((Map<?, ?>) weather.getPlugin().getConfigSchema()).containsKey("api_key"));
    }
}
