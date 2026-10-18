package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.service.impl.CapabilityRoutePreviewServiceImpl;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveSkillVO;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveToolVO;

class CapabilityRoutePreviewServiceImplTest {
    private final DeviceCapabilityService devices = mock(DeviceCapabilityService.class);
    private final CapabilityRoutePreviewServiceImpl service = new CapabilityRoutePreviewServiceImpl(devices);

    @Test
    void selectsAnUnambiguousDeterministicMatchAndShowsOnlyItsTools() {
        bundle("device-1", skill("skill-weather", 5, "天气", 10, "get_weather"));

        var result = service.preview("device-1", "上海天气怎么样");

        assertEquals(List.of("skill-weather"), result.getDeterministicMatches());
        assertFalse(result.isSemanticRequired());
        assertEquals("skill-weather", result.getSelectedSkillId());
        assertEquals(List.of("get_weather"), result.getAllowedTools());
    }

    @Test
    void equalRuleScoresRequireSemanticClassification() {
        bundle("device-1",
                skill("skill-weather", 0, "今日", 10, "get_weather"),
                skill("skill-news", 0, "今日", 10, "get_news_from_newsnow"));

        var result = service.preview("device-1", "今日有什么信息");

        assertTrue(result.isSemanticRequired());
        assertNull(result.getSelectedSkillId());
        assertEquals(List.of("skill-news", "skill-weather"), result.getEligibleSkillIds());
        assertEquals(List.of("get_news_from_newsnow", "get_weather"), result.getAllowedTools());
    }

    @Test
    void noRuleMatchOffersAllBoundSkillsToSemanticClassification() {
        bundle("device-1",
                skill("skill-weather", 1, "天气", 10, "get_weather"),
                skill("skill-news", 0, "新闻", 10, "get_news_from_newsnow"));

        var result = service.preview("device-1", "帮我看看外面怎么样");

        assertTrue(result.isSemanticRequired());
        assertEquals(List.of(), result.getDeterministicMatches());
        assertEquals(List.of("skill-weather", "skill-news"), result.getEligibleSkillIds());
    }

    private void bundle(String deviceId, EffectiveSkillVO... skills) {
        EffectiveCapabilityBundleVO bundle = new EffectiveCapabilityBundleVO();
        bundle.setDeviceId(deviceId);
        bundle.setSkills(List.of(skills));
        Map<String, EffectiveToolVO> tools = new LinkedHashMap<>();
        for (EffectiveSkillVO skill : skills) {
            for (String name : skill.getToolNames()) {
                EffectiveToolVO tool = new EffectiveToolVO();
                tool.setName(name);
                tools.put(name, tool);
            }
        }
        bundle.setTools(tools);
        when(devices.effectiveBundle(deviceId)).thenReturn(bundle);
    }

    private EffectiveSkillVO skill(String id, int bindingPriority, String keyword, int priority, String toolName) {
        EffectiveSkillVO skill = new EffectiveSkillVO();
        skill.setId(id);
        skill.setBindingPriority(bindingPriority);
        skill.setTriggers(List.of(Map.of(
                "type", "KEYWORD", "value", keyword, "priority", priority, "enabled", true)));
        skill.setToolNames(List.of(toolName));
        return skill;
    }
}
