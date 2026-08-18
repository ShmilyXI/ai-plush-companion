package xiaozhi.modules.companion.capability.packagefile;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO.Issue;

@Component
@RequiredArgsConstructor
public class SkillPackageValidator {
    private static final Set<String> TOOL_TYPES = Set.of("PLUGIN", "MCP", "ROLE_MCP", "DEVICE_TOOL");
    private static final Set<String> SECRET_KEYS = Set.of(
            "apikey", "secret", "token", "authorization", "password", "privatekey", "credential");

    private final PluginDefinitionDao pluginDao;
    private final McpServerDao mcpServerDao;
    private final McpToolSnapshotDao mcpToolDao;
    private final DeviceToolSnapshotDao deviceToolDao;

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
        Object version = manifest.get("version");
        if (!(version instanceof Number number) || number.intValue() < 1) {
            error(report, "INVALID_VERSION", "Skill version 必须是正整数");
        }

        if (!(manifest.get("runtime") instanceof Map<?, ?>)) {
            error(report, "MISSING_RUNTIME", "Skill runtime 配置不能为空");
        }
        if (!(manifest.get("triggers") instanceof Collection<?>)) {
            error(report, "MISSING_TRIGGERS", "Skill triggers 必须是数组");
        }

        validateTools(report, manifest.get("tools"));
        validateSecretRefs(report, manifest.get("secretRefs"));
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
            String type = text(tool.get("type"));
            String ref = text(tool.get("ref"));
            String name = text(tool.get("name"));
            boolean required = Boolean.TRUE.equals(tool.get("required"));
            if (type == null || !TOOL_TYPES.contains(type) || ref == null || name == null) {
                add(report, required, "UNKNOWN_TOOL", "Skill 工具引用无效");
                continue;
            }
            boolean found = switch (type) {
                case "PLUGIN" -> validPlugin(ref, name);
                case "MCP", "ROLE_MCP" -> validMcp(ref, name);
                case "DEVICE_TOOL" -> validDeviceTool(name);
                default -> false;
            };
            if (!found) add(report, required, "UNKNOWN_TOOL", "未找到可用工具: " + type + "/" + ref + "/" + name);
        }
    }

    private boolean validPlugin(String ref, String name) {
        PluginDefinitionEntity plugin = pluginDao.selectByCapabilityId(ref);
        return plugin != null && name.equals(plugin.getExecutorName());
    }

    private boolean validMcp(String ref, String name) {
        McpToolSnapshotEntity tool = mcpToolDao.selectById(ref);
        return tool != null && Integer.valueOf(1).equals(tool.getApproved()) && name.equals(tool.getToolName())
                && tool.getMcpServerId() != null && mcpServerDao.selectById(tool.getMcpServerId()) != null;
    }

    private boolean validDeviceTool(String name) {
        List<DeviceToolSnapshotEntity> rows = deviceToolDao.selectAvailableByToolName(name);
        return rows != null && !rows.isEmpty();
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

    private void findInlineSecrets(SkillPackageValidationVO report, Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String childKey = entry.getKey() == null ? null : entry.getKey().toString();
                if (childKey != null && SECRET_KEYS.contains(childKey.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT))
                        && !"secretRefs".equals(childKey) && entry.getValue() instanceof String string
                        && !string.isBlank()) {
                    error(report, "INLINE_SECRET", "Skill 包不能包含密钥值");
                }
                findInlineSecrets(report, entry.getValue(), childKey);
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> findInlineSecrets(report, item, key));
        }
    }

    private void requireNumber(SkillPackageValidationVO report, Map<String, Object> manifest, String key, int expected) {
        Object value = manifest.get(key);
        if (!(value instanceof Number number) || number.intValue() != expected) {
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
