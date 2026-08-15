package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.service.impl.CapabilityRoutePreviewServiceImpl;

class CapabilityRoutePreviewServiceImplTest {
    private final DeviceSkillMappingDao mappings = mock(DeviceSkillMappingDao.class);
    private final CapabilityDao capabilities = mock(CapabilityDao.class);
    private final CapabilityVersionDao versions = mock(CapabilityVersionDao.class);
    private final CapabilityRoutePreviewServiceImpl service = new CapabilityRoutePreviewServiceImpl(
            mappings, capabilities, versions);

    @Test
    void selectsAnUnambiguousDeterministicMatchAndShowsOnlyItsTools() {
        bind("device-1", mapping("skill-weather", 5));
        published("skill-weather", "天气", 10, "get_weather");

        var result = service.preview("device-1", "上海天气怎么样");

        assertEquals(List.of("skill-weather"), result.getDeterministicMatches());
        assertFalse(result.isSemanticRequired());
        assertEquals("skill-weather", result.getSelectedSkillId());
        assertEquals(List.of("get_weather"), result.getAllowedTools());
    }

    @Test
    void equalRuleScoresRequireSemanticClassification() {
        bind("device-1", mapping("skill-weather", 0), mapping("skill-news", 0));
        published("skill-weather", "今日", 10, "get_weather");
        published("skill-news", "今日", 10, "get_news_from_newsnow");

        var result = service.preview("device-1", "今日有什么信息");

        assertTrue(result.isSemanticRequired());
        assertNull(result.getSelectedSkillId());
        assertEquals(List.of("skill-news", "skill-weather"), result.getEligibleSkillIds());
        assertEquals(List.of("get_news_from_newsnow", "get_weather"), result.getAllowedTools());
    }

    @Test
    void noRuleMatchOffersAllBoundSkillsToSemanticClassification() {
        bind("device-1", mapping("skill-weather", 1), mapping("skill-news", 0));
        published("skill-weather", "天气", 10, "get_weather");
        published("skill-news", "新闻", 10, "get_news_from_newsnow");

        var result = service.preview("device-1", "帮我看看外面怎么样");

        assertTrue(result.isSemanticRequired());
        assertEquals(List.of(), result.getDeterministicMatches());
        assertEquals(List.of("skill-weather", "skill-news"), result.getEligibleSkillIds());
    }

    private void bind(String deviceId, DeviceSkillMappingEntity... rows) {
        when(mappings.selectEnabledByDevice(deviceId)).thenReturn(List.of(rows));
    }

    private DeviceSkillMappingEntity mapping(String skillId, int priority) {
        DeviceSkillMappingEntity row = new DeviceSkillMappingEntity();
        row.setSkillId(skillId);
        row.setVersionMode("LATEST");
        row.setTriggerPriority(priority);
        row.setEnabled(1);
        return row;
    }

    private void published(String skillId, String keyword, int priority, String toolName) {
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId(skillId);
        capability.setType("SKILL");
        capability.setStatus("PUBLISHED");
        capability.setPublishedVersion(1);
        when(capabilities.selectById(skillId)).thenReturn(capability);
        CapabilityVersionEntity version = new CapabilityVersionEntity();
        version.setCapabilityId(skillId);
        version.setVersionNo(1);
        version.setContentJson("{\"triggers\":[{\"type\":\"KEYWORD\",\"value\":\"" + keyword
                + "\",\"priority\":" + priority + ",\"enabled\":true}],\"tools\":[{\"toolName\":\""
                + toolName + "\"}]}");
        when(versions.selectVersion(skillId, 1)).thenReturn(version);
    }
}
