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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.init.CapabilityBootstrapService;
import xiaozhi.modules.companion.capability.init.LegacyPluginCapabilityMigrationService;
import xiaozhi.modules.companion.capability.service.CapabilityService;

class CapabilityBootstrapServiceTest {
    private final CapabilityDao capabilityDao = mock(CapabilityDao.class);
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private final Map<String, CapabilityEntity> stored = new LinkedHashMap<>();
    private final CapabilityBootstrapService service = new CapabilityBootstrapService(capabilityDao, capabilities);

    @BeforeEach
    void setup() {
        when(capabilityDao.selectById(any(String.class))).thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
        when(capabilityDao.insert(any(CapabilityEntity.class))).thenAnswer(invocation -> {
            CapabilityEntity entity = invocation.getArgument(0);
            stored.put(entity.getId(), entity);
            return 1;
        });
        when(capabilities.publish(eq(0L), any(String.class))).thenAnswer(invocation -> {
            CapabilityEntity entity = stored.get(invocation.getArgument(1));
            entity.setStatus("PUBLISHED");
            entity.setPublishedVersion(1);
            return null;
        });
    }

    @Test
    void repeatedBootstrapCreatesOnePublishedPluginAndSkillForEachOfficialTool() {
        LegacyPluginCapabilityMigrationService migration = mock(LegacyPluginCapabilityMigrationService.class);
        service.setLegacyMigration(migration);
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
    }

    @Test
    void officialSkillsUseMixedChineseTriggersAndExactlyOneExistingPluginTool() {
        service.initialize();

        ArgumentCaptor<CapabilitySaveDTO> drafts = ArgumentCaptor.forClass(CapabilitySaveDTO.class);
        verify(capabilities, times(6)).update(eq(0L), any(String.class), drafts.capture());
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
        assertEquals("get_news_from_newsnow", skills.get(1).getTools().get(0).getToolName());
        assertEquals("web_search", skills.get(2).getTools().get(0).getToolName());
        assertEquals(List.of("api_host", "api_key_secret_id", "location"),
                skills.get(0).getTools().get(0).getDefaultParams().keySet().stream().sorted().toList());
        assertEquals(List.of("category"),
                skills.get(1).getTools().get(0).getDefaultParams().keySet().stream().sorted().toList());
        assertEquals(List.of("api_key_secret_id", "max_results", "provider"),
                skills.get(2).getTools().get(0).getDefaultParams().keySet().stream().sorted().toList());
    }
}
