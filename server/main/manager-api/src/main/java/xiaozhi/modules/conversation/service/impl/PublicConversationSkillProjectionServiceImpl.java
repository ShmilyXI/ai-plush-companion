package xiaozhi.modules.conversation.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.conversation.service.PublicConversationSkillProjectionService;

@Service
public class PublicConversationSkillProjectionServiceImpl implements PublicConversationSkillProjectionService {
    private static final int MAX_PROMPT_LENGTH = 8_000;
    private final AgentVersionSkillBindingDao bindings;
    private final CapabilityDao capabilities;
    private final SkillPackageService packages;

    public PublicConversationSkillProjectionServiceImpl(AgentVersionSkillBindingDao bindings,
            CapabilityDao capabilities, SkillPackageService packages) {
        this.bindings = bindings;
        this.capabilities = capabilities;
        this.packages = packages;
    }

    @Override
    public List<Map<String, Object>> project(String agentId, Integer versionNo) {
        if (StringUtils.isBlank(agentId) || versionNo == null || versionNo <= 0) return List.of();
        List<AgentVersionSkillBindingEntity> rows = bindings.selectEnabledByAgentVersion(agentId, versionNo);
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentVersionSkillBindingEntity row : rows) {
            CapabilityEntity capability = capabilities.selectById(row.getSkillId());
            if (capability == null || !"SKILL".equals(capability.getType())
                    || !"PUBLISHED".equals(capability.getStatus())) continue;
            int selectedVersion = selectVersion(row, capability);
            if (selectedVersion <= 0) continue;
            SkillPackageEntity packageRow = packages.selectVersion(row.getSkillId(), selectedVersion);
            if (packageRow == null || (packageRow.getPublished() != null && packageRow.getPublished() != 1)) continue;
            Map<String, Object> manifest = parseManifest(packageRow.getManifestJson());
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("id", capability.getId());
            projected.put("version", selectedVersion);
            projected.put("packageVersion", packageRow.getVersionNo() == null ? selectedVersion : packageRow.getVersionNo());
            projected.put("packageSha256", StringUtils.defaultString(packageRow.getPackageSha256()));
            projected.put("name", capability.getName());
            projected.put("description", capability.getDescription());
            projected.put("executionPrompt", truncate(StringUtils.defaultIfBlank(packageRow.getSkillMarkdown(),
                    text(manifest.get("executionPrompt")))));
            projected.put("semanticThreshold", number(manifest.get("semanticThreshold"), 0.7));
            projected.put("responseMode", textOr(manifest.get("responseMode"), "LLM"));
            projected.put("timeoutMs", number(manifest.get("timeoutMs"), 30_000));
            projected.put("failureMessage", manifest.get("failureMessage"));
            projected.put("bindingPriority", row.getTriggerPriority() == null ? 0 : row.getTriggerPriority());
            projected.put("triggers", triggers(manifest.get("triggers")));
            // Public sessions may use the Skill prompt, but never inherit any executable tool.
            projected.put("toolNames", List.of());
            projected.put("defaults", Map.of());
            result.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projected)));
        }
        return List.copyOf(result);
    }

    private int selectVersion(AgentVersionSkillBindingEntity row, CapabilityEntity capability) {
        if ("FIXED".equalsIgnoreCase(row.getVersionMode())) {
            return row.getFixedVersion() == null ? 0 : row.getFixedVersion();
        }
        return capability.getPublishedVersion() == null ? 0 : capability.getPublishedVersion();
    }

    private Map<String, Object> parseManifest(String json) {
        if (StringUtils.isBlank(json)) return Map.of();
        Map<String, Object> value = JsonUtils.parseMap(json);
        return value == null ? Map.of() : value;
    }

    private List<Object> list(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    private List<Map<String, Object>> triggers(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> trigger = new LinkedHashMap<>();
            Object type = raw.get("type");
            Object triggerValue = raw.get("value");
            if (!(type instanceof String) || !(triggerValue instanceof String)
                    || ((String) type).isBlank() || ((String) triggerValue).isBlank()) continue;
            trigger.put("type", type);
            trigger.put("value", triggerValue);
            trigger.put("priority", raw.get("priority") instanceof Number ? raw.get("priority") : 0);
            trigger.put("caseSensitive", raw.get("caseSensitive") instanceof Boolean
                    ? raw.get("caseSensitive") : false);
            trigger.put("enabled", raw.get("enabled") instanceof Boolean ? raw.get("enabled") : true);
            result.add(trigger);
        }
        return result;
    }

    private String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private String textOr(Object value, String fallback) {
        String text = text(value);
        return StringUtils.isBlank(text) ? fallback : text;
    }

    private Number number(Object value, Number fallback) {
        return value instanceof Number number ? number : fallback;
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_PROMPT_LENGTH ? value : value.substring(0, MAX_PROMPT_LENGTH);
    }
}
