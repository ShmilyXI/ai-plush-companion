package zixuan.modules.companion.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.service.CompanionConfigService;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.common.constant.Constant;
import org.apache.commons.lang3.StringUtils;

@Service
public class CompanionConfigServiceImpl implements CompanionConfigService {
    private static final String DEFAULT_MODE = "turn_based";
    private static final String DEFAULT_PLANNER_PROMPT = "主动陪伴时少打扰。有真实切入点时优先回应；没有具体事实话题时，也可以用一句不依赖事实的自然陪伴短句表达在场感，但不得编造经历或重复固定问候。主动内容不超过两句，适合直接口播。";
    private final SysParamsService params;

    public CompanionConfigServiceImpl(SysParamsService params) {
        this.params = params;
    }

    @Override
    public Map<String, Object> build(DeviceEntity device, AgentEntity agent) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", Integer.valueOf(1).equals(agent.getCompanionEnabled()));
        config.put("mode", normalizeMode(device == null ? null : device.getCompanionMode()));
        config.put("proactive_planner_prompt", plannerPrompt());
        config.put("persona_prompt", agent.getSystemPrompt());
        config.put("relation_mode", agent.getRelationMode());
        config.put("user_address", agent.getUserAddress());
        // 屏幕表情不依赖陪伴模式；历史数据未设置(null)时默认开启，显式 0 才关闭
        config.put(
                "screen_expression_enabled",
                !Integer.valueOf(0).equals(agent.getScreenExpressionEnabled()));
        config.put("camera_preference_enabled", Integer.valueOf(1).equals(agent.getCameraPreferenceEnabled()));
        return config;
    }

    private String normalizeMode(String mode) {
        return "proactive".equals(mode) ? "proactive" : DEFAULT_MODE;
    }

    private String plannerPrompt() {
        if (params == null) return DEFAULT_PLANNER_PROMPT;
        String value = params.getValue(Constant.COMPANION_PROACTIVE_PLANNER_PROMPT, false);
        return StringUtils.isBlank(value) ? DEFAULT_PLANNER_PROMPT : value.trim();
    }
}
