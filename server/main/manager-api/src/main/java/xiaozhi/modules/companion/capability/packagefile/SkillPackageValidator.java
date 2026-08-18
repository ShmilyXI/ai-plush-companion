package xiaozhi.modules.companion.capability.packagefile;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO.Issue;
import xiaozhi.common.utils.JsonUtils;

@Component
@RequiredArgsConstructor
public class SkillPackageValidator {
    private static final Set<String> TOOL_TYPES = Set.of("PLUGIN", "MCP", "DEVICE_TOOL");
    private static final Set<String> TRIGGER_TYPES = Set.of(
            "KEYWORD", "REGEX", "POSITIVE_EXAMPLE", "NEGATIVE_EXAMPLE");
    private static final Set<String> DEVICE_REQUIREMENT_TYPES = Set.of(
            "DEVICE_MODEL", "MIN_FIRMWARE_VERSION", "MAX_FIRMWARE_VERSION", "REQUIRED_TOOL");
    private static final Set<String> RUNTIME_FIELDS = Set.of(
            "minVersion", "responseMode", "timeoutMs", "failureMessage", "semanticThreshold");
    private static final Set<String> TRIGGER_FIELDS = Set.of(
            "type", "value", "priority", "caseSensitive", "enabled");
    private static final Set<String> TOOL_FIELDS = Set.of(
            "type", "ref", "name", "required", "alias", "purpose", "defaults", "overridableFields");
    private static final Set<String> FORBIDDEN_CONFIG_KEYS = Set.of(
            "env", "environment", "headers", "connectionconfig", "approvedcommandtemplate", "command", "args");
    private static final Set<String> SECRET_KEYS = Set.of(
            "apikey", "secret", "token", "authorization", "password", "privatekey", "credential");

    private final PluginDefinitionDao pluginDao;
    private final McpServerDao mcpServerDao;
    private final McpToolSnapshotDao mcpToolDao;
    private final DeviceToolSnapshotDao deviceToolDao;
    private final CapabilityDao capabilityDao;

    public SkillPackageValidationVO validate(SkillPackageDocument document, String existingSkillId) {
        SkillPackageValidationVO report = new SkillPackageValidationVO();
        Map<String, Object> manifest = document.manifest();
        requireNumber(report, manifest, "schemaVersion", 1);
        String id = text(manifest.get("id"));
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            error(report, "INVALID_ID", "Skill id 无效");
        } else if (existingSkillId != null && !existingSkillId.equals(id)) {
            error(report, "IMMUTABLE_ID", "已存在 Skill 的 id 不能修改");
        }
        String name = text(manifest.get("name"));
        if (name == null) error(report, "MISSING_NAME", "Skill name 不能为空");
        if (manifest.containsKey("description") && manifest.get("description") != null
                && !(manifest.get("description") instanceof String)) {
            error(report, "INVALID_DESCRIPTION", "Skill description 必须是字符串");
        }
        Object version = manifest.get("version");
        if (!validInteger(version, 1, null)) {
            error(report, "INVALID_VERSION", "Skill version 必须是正整数");
        }
        if (document.markdown() == null || document.markdown().isBlank()) {
            error(report, "EMPTY_SKILL_MARKDOWN", "SKILL.md 不能为空");
        }

        if (!(manifest.get("runtime") instanceof Map<?, ?> runtime)) {
            error(report, "MISSING_RUNTIME", "Skill runtime 配置不能为空");
        } else {
            validateRuntime(report, runtime);
        }
        if (!(manifest.get("triggers") instanceof Collection<?> triggers)) {
            error(report, "MISSING_TRIGGERS", "Skill triggers 必须是数组");
        } else {
            validateTriggers(report, triggers);
        }

        validateTools(report, manifest.get("tools"));
        validateDeviceRequirements(report, manifest.get("deviceRequirements"));
        validateSecretRefs(report, manifest.get("secretRefs"));
        validateStringArray(report, manifest.get("overridableFields"), "INVALID_OVERRIDABLE_FIELDS",
                "overridableFields 必须是字符串数组");
        validateAssets(report, manifest.get("assets"), document.assets());
        findInlineSecrets(report, manifest, null);
        report.setStatus(report.errorCodes().isEmpty() ? "VALID" : "INVALID");
        return report;
    }

    private void validateTools(SkillPackageValidationVO report, Object rawTools) {
        if (!(rawTools instanceof Collection<?> tools)) {
            error(report, "MISSING_TOOLS", "Skill tools 必须是数组");
            return;
        }
        for (Object rawTool : tools) {
            if (!(rawTool instanceof Map<?, ?> tool)) {
                error(report, "INVALID_TOOL", "Skill 工具项必须是对象");
                continue;
            }
            rejectUnknownFields(report, tool, TOOL_FIELDS, "INVALID_TOOL_FIELD", "Skill 工具字段无效");
            String type = text(tool.get("type"));
            String ref = text(tool.get("ref"));
            String name = text(tool.get("name"));
            boolean required = !tool.containsKey("required") || Boolean.TRUE.equals(tool.get("required"));
            if (tool.containsKey("alias") && tool.get("alias") != null && !(tool.get("alias") instanceof String)) {
                error(report, "INVALID_TOOL_ALIAS", "Skill 工具 alias 必须是字符串");
            }
            if (tool.containsKey("purpose") && tool.get("purpose") != null && !(tool.get("purpose") instanceof String)) {
                error(report, "INVALID_TOOL_PURPOSE", "Skill 工具 purpose 必须是字符串");
            }
            if (tool.containsKey("defaults") && !(tool.get("defaults") instanceof Map<?, ?>)) {
                error(report, "INVALID_TOOL_DEFAULTS", "Skill 工具 defaults 必须是对象");
            }
            if (tool.containsKey("required") && !(tool.get("required") instanceof Boolean)) {
                error(report, "INVALID_TOOL_REQUIRED", "Skill 工具 required 必须是布尔值");
            }
            validateStringArray(report, tool.get("overridableFields"), "INVALID_TOOL_OVERRIDABLE_FIELDS",
                    "Skill 工具 overridableFields 必须是字符串数组");
            if (type == null || !TOOL_TYPES.contains(type) || ref == null || name == null) {
                add(report, required, "UNKNOWN_TOOL", "Skill 工具引用无效");
                continue;
            }
            boolean found = switch (type) {
                case "PLUGIN" -> validPlugin(ref, name);
                case "MCP" -> validMcp(ref, name);
                case "DEVICE_TOOL" -> validDeviceTool(name);
                default -> false;
            };
            if (!found) {
                add(report, required, "UNKNOWN_TOOL", "未找到可用工具: " + type + "/" + ref + "/" + name);
            } else {
                validateToolDefaults(report, type, ref, name, tool.get("defaults"));
            }
        }
    }

    private void validateRuntime(SkillPackageValidationVO report, Map<?, ?> runtime) {
        rejectUnknownFields(report, runtime, RUNTIME_FIELDS, "INVALID_RUNTIME_FIELD", "Skill runtime 字段无效");
        if (runtime.containsKey("minVersion") && (text(runtime.get("minVersion")) == null
                || !text(runtime.get("minVersion")).matches("\\d+(?:\\.\\d+){0,3}"))) {
            error(report, "INVALID_RUNTIME_MIN_VERSION", "Skill runtime minVersion 必须是版本号");
        }
        Object responseMode = runtime.get("responseMode");
        if (!(responseMode instanceof String value)
                || !Set.of("LLM", "FIXED").contains(value.trim().toUpperCase(Locale.ROOT))) {
            error(report, "INVALID_RUNTIME_RESPONSE_MODE", "Skill runtime responseMode 必须是 LLM 或 FIXED");
        }

        Object timeout = runtime.get("timeoutMs");
        if (!validInteger(timeout, 1000, 120000)) {
            error(report, "INVALID_RUNTIME_TIMEOUT", "Skill runtime timeoutMs 必须是 1000 到 120000 的整数");
        }

        Object threshold = runtime.get("semanticThreshold");
        if (threshold == null) {
            error(report, "MISSING_RUNTIME_SEMANTIC_THRESHOLD", "Skill runtime semanticThreshold 不能为空");
        } else if (!validDecimal(threshold, BigDecimal.ZERO, BigDecimal.ONE)) {
            error(report, "INVALID_RUNTIME_SEMANTIC_THRESHOLD", "Skill runtime semanticThreshold 必须在 0 到 1 之间");
        }
        if (runtime.containsKey("failureMessage") && runtime.get("failureMessage") != null
                && !(runtime.get("failureMessage") instanceof String)) {
            error(report, "INVALID_RUNTIME_FAILURE_MESSAGE", "Skill runtime failureMessage 必须是字符串");
        }
    }

    private void validateTriggers(SkillPackageValidationVO report, Collection<?> triggers) {
        for (Object value : triggers) {
            if (!(value instanceof Map<?, ?> trigger)) {
                error(report, "INVALID_TRIGGER", "Skill 触发规则必须是对象");
                continue;
            }
            rejectUnknownFields(report, trigger, TRIGGER_FIELDS, "INVALID_TRIGGER_FIELD", "Skill 触发规则字段无效");
            Object type = trigger.get("type");
            if (!(type instanceof String name) || !TRIGGER_TYPES.contains(name.trim().toUpperCase(Locale.ROOT))) {
                error(report, "INVALID_TRIGGER_TYPE", "Skill 触发规则类型无效");
            }
            Object pattern = trigger.get("value");
            if (!(pattern instanceof String text) || text.isBlank()) {
                error(report, "INVALID_TRIGGER_VALUE", "Skill 触发规则内容不能为空");
            } else if (type instanceof String name && "REGEX".equalsIgnoreCase(name.trim())) {
                try {
                    Pattern.compile(text);
                } catch (PatternSyntaxException exception) {
                    error(report, "INVALID_TRIGGER_REGEX", "Skill REGEX 触发规则无效");
                }
            }
            if (!validInteger(trigger.get("priority"), null, null)) {
                error(report, "INVALID_TRIGGER_PRIORITY", "Skill 触发规则 priority 必须是整数");
            }
            if (trigger.containsKey("caseSensitive") && !(trigger.get("caseSensitive") instanceof Boolean)) {
                error(report, "INVALID_TRIGGER_CASE_SENSITIVE", "Skill 触发规则 caseSensitive 必须是布尔值");
            }
            if (trigger.containsKey("enabled") && !(trigger.get("enabled") instanceof Boolean)) {
                error(report, "INVALID_TRIGGER_ENABLED", "Skill 触发规则 enabled 必须是布尔值");
            }
        }
    }

    private boolean validInteger(Object value, int minimum, int maximum) {
        return validInteger(value, Integer.valueOf(minimum), Integer.valueOf(maximum));
    }

    private boolean validInteger(Object value, Integer minimum, Integer maximum) {
        if (!(value instanceof Number number)) return false;
        try {
            BigDecimal decimal = new BigDecimal(number.toString());
            return decimal.scale() <= 0
                    && (minimum == null || decimal.compareTo(BigDecimal.valueOf(minimum)) >= 0)
                    && (maximum == null || decimal.compareTo(BigDecimal.valueOf(maximum)) <= 0);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean validDecimal(Object value, BigDecimal minimum, BigDecimal maximum) {
        if (!(value instanceof Number number)) return false;
        try {
            BigDecimal decimal = new BigDecimal(number.toString());
            return decimal.compareTo(minimum) >= 0 && decimal.compareTo(maximum) <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean validPlugin(String ref, String name) {
        PluginDefinitionEntity plugin = pluginDao.selectByCapabilityId(ref);
        CapabilityEntity capability = capabilityDao.selectById(ref);
        return plugin != null && name.equals(plugin.getExecutorName()) && capability != null
                && "PLUGIN".equals(capability.getType()) && "PUBLISHED".equals(capability.getStatus());
    }

    private boolean validMcp(String ref, String name) {
        McpToolSnapshotEntity tool = mcpToolDao.selectById(ref);
        var server = tool == null || tool.getMcpServerId() == null ? null : mcpServerDao.selectById(tool.getMcpServerId());
        CapabilityEntity capability = server == null ? null : capabilityDao.selectById(server.getCapabilityId());
        return tool != null && Integer.valueOf(1).equals(tool.getApproved())
                && "ACTIVE".equalsIgnoreCase(tool.getStatus()) && name.equals(tool.getToolName())
                && server != null && capability != null && "MCP_SERVER".equals(capability.getType())
                && "PUBLISHED".equals(capability.getStatus());
    }

    private boolean validDeviceTool(String name) {
        List<DeviceToolSnapshotEntity> rows = deviceToolDao.selectAvailableByToolName(name);
        return rows != null && !rows.isEmpty();
    }

    private void validateToolDefaults(SkillPackageValidationVO report, String type, String ref, String name,
            Object rawDefaults) {
        if (!(rawDefaults instanceof Map<?, ?> defaults)) return;
        Map<String, Object> schemas = switch (type) {
            case "PLUGIN" -> pluginDefaultSchemas(ref);
            case "MCP" -> mcpDefaultSchemas(ref);
            case "DEVICE_TOOL" -> deviceDefaultSchemas(name);
            default -> Map.of();
        };
        for (var entry : defaults.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String field) || !schemas.containsKey(field)) {
                error(report, "UNKNOWN_TOOL_DEFAULT", "工具默认参数未在登记 Schema 中声明: " + key);
            } else if (!matchesSchema(entry.getValue(), schemas.get(field))) {
                error(report, "INVALID_TOOL_DEFAULT_VALUE", "工具默认参数不符合登记 Schema: " + field);
            }
        }
    }

    private Map<String, Object> pluginDefaultSchemas(String capabilityId) {
        PluginDefinitionEntity plugin = pluginDao.selectByCapabilityId(capabilityId);
        if (plugin == null) return Map.of();
        Map<String, Object> result = schemaProperties(plugin.getInputSchemaJson());
        result.putAll(schemaProperties(plugin.getConfigSchemaJson()));
        Object secretFields = parse(plugin.getSecretFieldsJson());
        if (secretFields instanceof Collection<?> fields) {
            fields.forEach(field -> {
                String name = text(field);
                if (name != null) result.put(name + "_secret_id", Map.of("type", "string"));
            });
        }
        return result;
    }

    private Map<String, Object> mcpDefaultSchemas(String snapshotId) {
        McpToolSnapshotEntity snapshot = mcpToolDao.selectById(snapshotId);
        return snapshot == null ? Map.of() : schemaProperties(snapshot.getInputSchemaJson());
    }

    private Map<String, Object> deviceDefaultSchemas(String toolName) {
        List<DeviceToolSnapshotEntity> rows = deviceToolDao.selectAvailableByToolName(toolName);
        return rows == null || rows.isEmpty() ? Map.of() : schemaProperties(rows.get(0).getInputSchemaJson());
    }

    private Map<String, Object> schemaProperties(String json) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        Map<String, Object> schema = parse(json) instanceof Map<?, ?> raw ? toMap(raw) : Map.of();
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String name) result.put(name, value);
            });
        }
        return result;
    }

    private boolean matchesSchema(Object value, Object rawSchema) {
        if (rawSchema instanceof Boolean allowed) return allowed;
        if (!(rawSchema instanceof Map<?, ?> raw)) return false;
        Map<String, Object> schema = toMap(raw);
        Object enumValues = schema.get("enum");
        if (enumValues instanceof Collection<?> values
                && values.stream().noneMatch(candidate -> sameJsonValue(candidate, value))) {
            return false;
        }
        if (schema.containsKey("const") && !sameJsonValue(schema.get("const"), value)) return false;
        if (!matchesType(value, schema.get("type"))) return false;
        if (value instanceof Number number && !matchesNumber(number, schema)) return false;
        if (value instanceof String text && !matchesString(text, schema)) return false;
        if (value instanceof Collection<?> values && !matchesArray(values, schema)) return false;
        return !(value instanceof Map<?, ?> values) || matchesObject(values, schema);
    }

    private boolean matchesType(Object value, Object rawType) {
        if (rawType == null) return true;
        if (rawType instanceof Collection<?> types) {
            return types.stream().anyMatch(type -> type instanceof String name && matchesType(value, name));
        }
        if (!(rawType instanceof String type)) return false;
        return switch (type) {
            case "null" -> value == null;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && integer(number);
            case "array" -> value instanceof Collection<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> false;
        };
    }

    private boolean matchesNumber(Number number, Map<String, Object> schema) {
        BigDecimal value;
        try {
            value = new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            return false;
        }
        return decimalBoundary(value, schema.get("minimum"), true, false)
                && decimalBoundary(value, schema.get("maximum"), false, false)
                && decimalBoundary(value, schema.get("exclusiveMinimum"), true, true)
                && decimalBoundary(value, schema.get("exclusiveMaximum"), false, true);
    }

    private boolean decimalBoundary(BigDecimal value, Object rawBoundary, boolean minimum, boolean exclusive) {
        if (!(rawBoundary instanceof Number number)) return rawBoundary == null;
        try {
            int comparison = value.compareTo(new BigDecimal(number.toString()));
            return minimum ? exclusive ? comparison > 0 : comparison >= 0
                    : exclusive ? comparison < 0 : comparison <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean matchesString(String value, Map<String, Object> schema) {
        if (!lengthBoundary(value.length(), schema.get("minLength"), true)
                || !lengthBoundary(value.length(), schema.get("maxLength"), false)) return false;
        Object rawPattern = schema.get("pattern");
        if (rawPattern == null) return true;
        if (!(rawPattern instanceof String pattern)) return false;
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException exception) {
            return false;
        }
    }

    private boolean lengthBoundary(int value, Object rawBoundary, boolean minimum) {
        if (rawBoundary == null) return true;
        if (!(rawBoundary instanceof Number number) || !integer(number)) return false;
        int boundary = number.intValue();
        return boundary >= 0 && (minimum ? value >= boundary : value <= boundary);
    }

    private boolean matchesArray(Collection<?> values, Map<String, Object> schema) {
        if (!lengthBoundary(values.size(), schema.get("minItems"), true)
                || !lengthBoundary(values.size(), schema.get("maxItems"), false)) return false;
        Object itemSchema = schema.get("items");
        return itemSchema == null || values.stream().allMatch(value -> matchesSchema(value, itemSchema));
    }

    private boolean matchesObject(Map<?, ?> values, Map<String, Object> schema) {
        Object required = schema.get("required");
        if (required instanceof Collection<?> fields
                && fields.stream().anyMatch(field -> !(field instanceof String name) || !values.containsKey(name))) {
            return false;
        }
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> raw
                ? toMap(raw) : Map.of();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)) return false;
            if (properties.containsKey(name) && !matchesSchema(entry.getValue(), properties.get(name))) return false;
            if (!properties.containsKey(name) && Boolean.FALSE.equals(schema.get("additionalProperties"))) return false;
        }
        return true;
    }

    private boolean sameJsonValue(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            try {
                return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString())) == 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return java.util.Objects.equals(left, right);
    }

    private boolean integer(Number number) {
        try {
            return new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private Map<String, Object> toMap(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Object parse(String json) {
        return json == null || json.isBlank() ? null : JsonUtils.parseObject(json, Object.class);
    }

    private void validateSecretRefs(SkillPackageValidationVO report, Object rawRefs) {
        if (rawRefs == null) return;
        if (!(rawRefs instanceof Collection<?> refs)) {
            error(report, "INVALID_SECRET_REFS", "secretRefs 必须是数组");
            return;
        }
        for (Object ref : refs) {
            if (!(ref instanceof String value) || value.isBlank() || !value.matches("[A-Za-z0-9._-]{1,128}")) {
                error(report, "INVALID_SECRET_REF", "secretRefs 只能包含密钥名称");
            }
        }
    }

    private void validateAssets(SkillPackageValidationVO report, Object rawAssets, Map<String, byte[]> files) {
        if (rawAssets == null) return;
        if (!(rawAssets instanceof Collection<?> assets)) {
            error(report, "INVALID_ASSETS", "assets 必须是字符串数组");
            return;
        }
        for (Object raw : assets) {
            String path = text(raw);
            if (path == null || !path.startsWith("assets/") || !files.containsKey(path)) {
                error(report, "INVALID_ASSET_REF", "assets 包含不存在的资源: " + raw);
            }
        }
    }

    private void validateStringArray(SkillPackageValidationVO report, Object raw, String code, String message) {
        if (raw == null) return;
        if (!(raw instanceof Collection<?> values)
                || values.stream().anyMatch(value -> text(value) == null)) {
            error(report, code, message);
        }
    }

    private void rejectUnknownFields(SkillPackageValidationVO report, Map<?, ?> value, Set<String> allowed,
            String code, String message) {
        for (Object key : value.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                error(report, code, message + ": " + key);
            }
        }
    }

    private void validateDeviceRequirements(SkillPackageValidationVO report, Object rawRequirements) {
        if (rawRequirements == null) return;
        if (rawRequirements instanceof Collection<?> requirements) {
            for (Object raw : requirements) {
                if (!(raw instanceof Map<?, ?> requirement)) {
                    error(report, "INVALID_DEVICE_REQUIREMENT", "deviceRequirements 项必须是对象");
                    continue;
                }
                String type = text(requirement.get("type"));
                String value = text(requirement.get("value"));
                if (type == null || !DEVICE_REQUIREMENT_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
                    error(report, "INVALID_DEVICE_REQUIREMENT_TYPE", "设备要求类型无效");
                }
                if (value == null) {
                    error(report, "INVALID_DEVICE_REQUIREMENT_VALUE", "设备要求值不能为空");
                }
            }
            return;
        }
        if (rawRequirements instanceof Map<?, ?> requirements) {
            for (Object key : requirements.keySet()) {
                if (!Set.of("models", "deviceModels", "minFirmwareVersion", "maxFirmwareVersion", "requiredTools")
                        .contains(String.valueOf(key))) {
                    error(report, "INVALID_DEVICE_REQUIREMENT_FIELD", "设备要求字段无效: " + key);
                }
            }
            validateStringCollection(report, requirements.get("models"), "models");
            validateStringCollection(report, requirements.get("deviceModels"), "deviceModels");
            validateStringCollection(report, requirements.get("requiredTools"), "requiredTools");
            validateVersionValue(report, requirements.get("minFirmwareVersion"), "minFirmwareVersion");
            validateVersionValue(report, requirements.get("maxFirmwareVersion"), "maxFirmwareVersion");
            return;
        }
        error(report, "INVALID_DEVICE_REQUIREMENTS", "deviceRequirements 必须是数组或对象");
    }

    private void validateStringCollection(SkillPackageValidationVO report, Object value, String field) {
        if (value == null) return;
        if (!(value instanceof Collection<?> values)) {
            error(report, "INVALID_DEVICE_REQUIREMENT_" + field.toUpperCase(Locale.ROOT), field + " 必须是字符串数组");
            return;
        }
        for (Object item : values) {
            if (text(item) == null) {
                error(report, "INVALID_DEVICE_REQUIREMENT_" + field.toUpperCase(Locale.ROOT), field + " 只能包含非空字符串");
            }
        }
    }

    private void validateVersionValue(SkillPackageValidationVO report, Object value, String field) {
        if (value == null) return;
        if (text(value) == null || !text(value).matches("\\d+(?:\\.\\d+){0,3}")) {
            error(report, "INVALID_DEVICE_REQUIREMENT_" + field.toUpperCase(Locale.ROOT), field + " 必须是版本号");
        }
    }

    private void findInlineSecrets(SkillPackageValidationVO report, Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String childKey = entry.getKey() == null ? null : entry.getKey().toString();
                String normalizedKey = childKey == null ? "" : childKey.replaceAll("[^A-Za-z0-9]", "")
                        .toLowerCase(Locale.ROOT);
                if (FORBIDDEN_CONFIG_KEYS.contains(normalizedKey) && hasValue(entry.getValue())) {
                    error(report, "FORBIDDEN_CONFIGURATION", "Skill 包不能包含环境变量、连接配置或命令");
                }
                if (childKey != null && isSecretKey(childKey) && !"secretRefs".equalsIgnoreCase(childKey)
                        && isInlineSecretValue(entry.getValue())) {
                    error(report, "INLINE_SECRET", "Skill 包不能包含密钥值");
                }
                findInlineSecrets(report, entry.getValue(), childKey);
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> findInlineSecrets(report, item, key));
        }
    }

    private boolean isSecretKey(String key) {
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SECRET_KEYS.contains(normalized)
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.endsWith("token")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("privatekey");
    }

    private boolean isInlineSecretValue(Object value) {
        return value instanceof String string ? !string.isBlank()
                : value instanceof Number || value instanceof Boolean;
    }

    private boolean hasValue(Object value) {
        if (value == null) return false;
        if (value instanceof String string) return !string.isBlank();
        if (value instanceof Collection<?> collection) return !collection.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private void requireNumber(SkillPackageValidationVO report, Map<String, Object> manifest, String key, int expected) {
        Object value = manifest.get(key);
        if (!validInteger(value, expected, expected)) {
            error(report, "INVALID_" + key.toUpperCase(Locale.ROOT), key + " 必须为 " + expected);
        }
    }

    private void add(SkillPackageValidationVO report, boolean required, String code, String message) {
        report.getIssues().add(new Issue(required ? "ERROR" : "WARNING", code, message));
    }

    private void error(SkillPackageValidationVO report, String code, String message) {
        report.getIssues().add(new Issue("ERROR", code, message));
    }

    private String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text.trim();
    }
}
