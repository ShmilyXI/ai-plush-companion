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
    private static final int DEFAULT_IDLE_FIRST_CHECK_MS = 45_000;
    private static final int DEFAULT_IDLE_BACKOFF_MIN_MS = 60_000;
    private static final int DEFAULT_IDLE_BACKOFF_MAX_MS = 900_000;
    private static final int DEFAULT_PROACTIVE_MAX_CHARS = 80;
    private static final int DEFAULT_PROACTIVE_MAX_CONTEXT_CHARS = 12_000;
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
        config.put("proactive_guidance", StringUtils.defaultString(agent.getPersonality()).trim());
        config.put("personality", StringUtils.defaultString(agent.getPersonality()).trim());
        config.put("idle_first_check_ms", integerParam(
                "companion.proactive_idle_first_check_ms", DEFAULT_IDLE_FIRST_CHECK_MS, 5_000, 3_600_000));
        config.put("idle_backoff_min_ms", integerParam(
                "companion.proactive_idle_backoff_min_ms", DEFAULT_IDLE_BACKOFF_MIN_MS, 5_000, 3_600_000));
        config.put("idle_backoff_max_ms", integerParam(
                "companion.proactive_idle_backoff_max_ms", DEFAULT_IDLE_BACKOFF_MAX_MS, 5_000, 86_400_000));
        config.put("idle_jitter_ms", integerParam(
                "companion.proactive_idle_jitter_ms", 5_000, 0, 300_000));
        config.put("proactive_max_chars", integerParam(
                "companion.proactive_max_chars", DEFAULT_PROACTIVE_MAX_CHARS, 20, 200));
        config.put("proactive_max_context_chars", integerParam(
                "companion.proactive_max_context_chars", DEFAULT_PROACTIVE_MAX_CONTEXT_CHARS, 1_000, 50_000));
        config.put("proactive_min_memory_confidence", decimalParam(
                "companion.proactive_min_memory_confidence", 0.5, 0.0, 1.0));
        config.put("activity_min_rms", integerParam(
                "companion.proactive_activity_min_rms", 160, 1, 32_767));
        config.put("activity_noise_ratio", decimalParam(
                "companion.proactive_activity_noise_ratio", 2.0, 1.0, 20.0));
        config.put("activity_min_active_frames", integerParam(
                "companion.proactive_activity_min_active_frames", 1, 1, 20));
        config.put("activity_min_active_ms", integerParam(
                "companion.proactive_activity_min_active_ms", 0, 0, 2_000));
        config.put("require_realtime_aec", true);
        config.put("memory_enabled", agent.getMemoryEnabled() == null || agent.getMemoryEnabled() == 1);
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
        return StringUtils.isBlank(value) || value.trim().length() > 2000
                ? DEFAULT_PLANNER_PROMPT : value.trim();
    }

    private int integerParam(String code, int fallback, int min, int max) {
        if (params == null) return fallback;
        String raw = params.getValue(code, false);
        try {
            int value = Integer.parseInt(StringUtils.trimToEmpty(raw));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private double decimalParam(String code, double fallback, double min, double max) {
        if (params == null) return fallback;
        String raw = params.getValue(code, false);
        try {
            double value = Double.parseDouble(StringUtils.trimToEmpty(raw));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
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
