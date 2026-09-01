package xiaozhi.modules.companion.service.impl;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.service.CompanionConfigService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.common.constant.Constant;
import org.apache.commons.lang3.StringUtils;

@Service
public class CompanionConfigServiceImpl implements CompanionConfigService {
    private static final Set<String> ALLOWED_CUES = Set.of("laugh", "sigh", "hesitate", "breathe");
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
        config.put("cue_files", parseCueFiles(agent.getCompanionCueConfig()));
        config.put("screen_expression_enabled", Integer.valueOf(1).equals(agent.getScreenExpressionEnabled()));
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

    private Map<String, String> parseCueFiles(String rawConfig) {
        if (rawConfig == null || rawConfig.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> rawCueFiles = JsonUtils.parseMap(rawConfig);
            if (rawCueFiles == null) {
                return Collections.emptyMap();
            }
            Map<String, String> cueFiles = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : rawCueFiles.entrySet()) {
                if (ALLOWED_CUES.contains(entry.getKey())
                        && entry.getValue() instanceof String path
                        && isSafeRelativePath(path)) {
                    cueFiles.put(entry.getKey(), path);
                }
            }
            return cueFiles;
        } catch (RuntimeException exception) {
            return Collections.emptyMap();
        }
    }

    private boolean isSafeRelativePath(String rawPath) {
        if (rawPath.isBlank()
                || rawPath.indexOf('\0') >= 0
                || rawPath.contains("\\")
                || rawPath.contains(":")) {
            return false;
        }
        try {
            Path path = Path.of(rawPath);
            return !path.isAbsolute() && !path.normalize().startsWith("..");
        } catch (InvalidPathException exception) {
            return false;
        }
    }
}
