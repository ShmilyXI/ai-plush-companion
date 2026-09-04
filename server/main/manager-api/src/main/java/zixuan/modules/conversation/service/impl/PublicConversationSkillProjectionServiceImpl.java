package zixuan.modules.conversation.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import zixuan.common.utils.JsonUtils;
import zixuan.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import zixuan.modules.companion.capability.dao.CapabilityDao;
import zixuan.modules.companion.capability.dao.PluginDefinitionDao;
import zixuan.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import zixuan.modules.companion.capability.entity.CapabilityEntity;
import zixuan.modules.companion.capability.entity.SkillPackageEntity;
import zixuan.modules.companion.capability.entity.PluginDefinitionEntity;
import zixuan.modules.companion.capability.service.SkillPackageService;
import zixuan.modules.conversation.service.PublicConversationCapabilityProjection;
import zixuan.modules.conversation.service.PublicConversationSkillProjectionService;

@Service
public class PublicConversationSkillProjectionServiceImpl implements PublicConversationSkillProjectionService {
    private static final int MAX_PROMPT_LENGTH = 8_000;
    private static final Map<String, String> PUBLIC_READONLY_PLUGINS = Map.of(
            "plugin-weather", "get_weather",
            "plugin-news", "get_news_from_newsnow");
    private final AgentVersionSkillBindingDao bindings;
    private final CapabilityDao capabilities;
    private final SkillPackageService packages;
    private final PluginDefinitionDao plugins;

    public PublicConversationSkillProjectionServiceImpl(AgentVersionSkillBindingDao bindings,
            CapabilityDao capabilities, SkillPackageService packages, PluginDefinitionDao plugins) {
        this.bindings = bindings;
        this.capabilities = capabilities;
        this.packages = packages;
        this.plugins = plugins;
    }

    @Override
    public PublicConversationCapabilityProjection project(String agentId, Integer versionNo) {
        if (StringUtils.isBlank(agentId) || versionNo == null || versionNo <= 0) {
            return PublicConversationCapabilityProjection.empty();
        }
        List<AgentVersionSkillBindingEntity> rows = bindings.selectEnabledByAgentVersion(agentId, versionNo);
        if (rows == null || rows.isEmpty()) return PublicConversationCapabilityProjection.empty();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> projectedTools = new LinkedHashMap<>();
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
            List<String> toolNames = new ArrayList<>();
            for (Object rawTool : list(manifest.get("tools"))) {
                Map<String, Object> tool = projectTool(rawTool);
                if (tool == null) continue;
                String name = text(tool.get("name"));
                projectedTools.putIfAbsent(name, tool);
                toolNames.add(name);
            }
            projected.put("toolNames", List.copyOf(toolNames));
            projected.put("defaults", Map.of());
            result.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projected)));
        }
        return new PublicConversationCapabilityProjection(
                List.copyOf(result), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projectedTools)));
    }

    private Map<String, Object> projectTool(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        String type = text(raw.get("type")).toUpperCase();
        String refId = text(raw.get("ref"));
        String name = text(raw.get("name"));
        if (!"PLUGIN".equals(type) || !name.equals(PUBLIC_READONLY_PLUGINS.get(refId))) return null;
        CapabilityEntity pluginCapability = capabilities.selectById(refId);
        if (pluginCapability == null || !"PLUGIN".equals(pluginCapability.getType())
                || !"PUBLISHED".equals(pluginCapability.getStatus())) return null;
        PluginDefinitionEntity plugin = plugins.selectByCapabilityId(refId);
        if (plugin == null || !name.equals(plugin.getExecutorName())) return null;
        Map<String, Object> inputSchema = parseManifest(plugin.getInputSchemaJson());
        if (!"object".equals(inputSchema.get("type"))) return null;

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", StringUtils.defaultString(pluginCapability.getDescription()));
        function.put("parameters", inputSchema);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", java.util.Collections.unmodifiableMap(function));
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("executor", "SERVER_PLUGIN");
        runtime.put("schema", java.util.Collections.unmodifiableMap(schema));

        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("name", name);
        projected.put("type", "PLUGIN");
        projected.put("refId", refId);
        projected.put("required", !raw.containsKey("required") || Boolean.TRUE.equals(raw.get("required")));
        projected.put("defaults", sanitizedDefaults(raw.get("defaults")));
        projected.put("runtime", java.util.Collections.unmodifiableMap(runtime));
        return java.util.Collections.unmodifiableMap(projected);
    }

    private Map<String, Object> sanitizedDefaults(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name) || sensitive(name)) return;
            result.put(name, item);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private boolean sensitive(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("_secret_id") || normalized.contains("api_key")
                || normalized.contains("token") || normalized.contains("password")
                || normalized.contains("secret");
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
