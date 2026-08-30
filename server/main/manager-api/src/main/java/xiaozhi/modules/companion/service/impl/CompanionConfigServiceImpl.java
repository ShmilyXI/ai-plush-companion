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

@Service
public class CompanionConfigServiceImpl implements CompanionConfigService {
    private static final Set<String> ALLOWED_CUES = Set.of("laugh", "sigh", "hesitate", "breathe");

    @Override
    public Map<String, Object> build(DeviceEntity device, AgentEntity agent) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", Integer.valueOf(1).equals(agent.getCompanionEnabled()));
        config.put("persona_prompt", agent.getSystemPrompt());
        config.put("relation_mode", agent.getRelationMode());
        config.put("user_address", agent.getUserAddress());
        config.put("cue_files", parseCueFiles(agent.getCompanionCueConfig()));
        config.put("screen_expression_enabled", Integer.valueOf(1).equals(agent.getScreenExpressionEnabled()));
        config.put("camera_preference_enabled", Integer.valueOf(1).equals(agent.getCameraPreferenceEnabled()));
        config.put("memory_enabled", agent.getMemoryEnabled() == null || agent.getMemoryEnabled() == 1);
        config.put("profile_memory_namespace", agent.getUserId() == null || agent.getId() == null
                ? null : "companion:" + agent.getUserId() + ":" + agent.getId());
        return config;
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
