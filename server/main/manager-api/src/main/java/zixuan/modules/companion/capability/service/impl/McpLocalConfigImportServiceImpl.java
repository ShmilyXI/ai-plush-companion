package zixuan.modules.companion.capability.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.capability.dao.CapabilitySecretDao;
import zixuan.modules.companion.capability.dto.CapabilitySaveDTO;
import zixuan.modules.companion.capability.dto.McpServerDTO;
import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;
import zixuan.modules.companion.capability.service.CapabilitySecretService;
import zixuan.modules.companion.capability.service.CapabilityService;
import zixuan.modules.companion.capability.service.McpLocalConfigImportService;
import zixuan.modules.companion.capability.vo.McpLocalConfigImportVO;

@Service
@AllArgsConstructor
public class McpLocalConfigImportServiceImpl implements McpLocalConfigImportService {
    private final CapabilityService capabilities;
    private final CapabilitySecretService secrets;
    private final CapabilitySecretDao secretDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpLocalConfigImportVO importDocument(Long operatorId, Map<String, Object> document) {
        if (operatorId == null || document == null || !(document.get("mcpServers") instanceof Map<?, ?> rawServers)) {
            throw new RenException("MCP 本地配置格式无效");
        }
        Set<String> managedNames = capabilities.page("MCP_SERVER", null, null, 1, 100).getList().stream()
                .map(item -> normalizeName(item.getName())).collect(Collectors.toSet());
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Object> ordered = new TreeMap<>();
        rawServers.forEach((key, value) -> ordered.put(String.valueOf(key), value));
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            String name = StringUtils.trimToNull(entry.getKey());
            if (name == null || !(entry.getValue() instanceof Map<?, ?> rawConfig)) {
                throw new RenException("MCP 服务配置无效");
            }
            if (managedNames.contains(normalizeName(name))) {
                skipped.add(name);
                continue;
            }
            importServer(operatorId, name, rawConfig);
            imported.add(name);
            managedNames.add(normalizeName(name));
        }
        McpLocalConfigImportVO result = new McpLocalConfigImportVO();
        result.setImported(List.copyOf(imported));
        result.setSkipped(List.copyOf(skipped));
        return result;
    }

    private void importServer(Long operatorId, String name, Map<?, ?> rawConfig) {
        ImportedConfig imported = sanitize(rawConfig);
        CapabilitySaveDTO request = request(name, imported, Map.of());
        var created = capabilities.create(operatorId, request);
        String capabilityId = created == null ? null : created.getId();
        if (StringUtils.isBlank(capabilityId)) throw new RenException("MCP 导入未生成能力编号");

        Map<String, String> refs = new LinkedHashMap<>();
        for (SecretValue secret : imported.secrets()) {
            String secretName = secretName(secret.path());
            secrets.save(operatorId, capabilityId, secretName, secret.value());
            CapabilitySecretEntity stored = secretDao.selectByCapabilityAndName(capabilityId, secretName);
            if (stored == null) throw new RenException("MCP 导入密钥保存失败");
            refs.put(secret.path(), stored.getId());
        }
        capabilities.update(operatorId, capabilityId, request(name, imported, refs));
        capabilities.publish(operatorId, capabilityId);
    }

    private CapabilitySaveDTO request(String name, ImportedConfig imported, Map<String, String> refs) {
        McpServerDTO mcp = new McpServerDTO();
        mcp.setTransport(imported.transport());
        mcp.setConnectionConfig(imported.connectionConfig());
        mcp.setSecretRefs(Map.copyOf(refs));
        mcp.setApprovedCommandTemplate(imported.approvedCommandTemplate());
        CapabilitySaveDTO request = new CapabilitySaveDTO();
        request.setType("MCP_SERVER");
        request.setName(name);
        request.setDescription("从本地 .mcp_server_settings.json 导入");
        request.setMcp(mcp);
        return request;
    }

    private ImportedConfig sanitize(Map<?, ?> source) {
        Map<String, Object> connection = new LinkedHashMap<>();
        List<SecretValue> secretValues = new ArrayList<>();
        String command = text(source.get("command"));
        String url = text(source.get("url"));
        String transport;
        Map<String, Object> template = null;
        if (StringUtils.isNotBlank(command)) {
            transport = "STDIO";
            connection.put("command", command);
            List<Object> args = source.get("args") instanceof List<?> values ? List.copyOf(values) : List.of();
            connection.put("args", args);
            template = ApprovedMcpCommandTemplates.resolve(connection);
            if (template == null) throw new RenException("MCP stdio 命令不在服务端批准模板中");
        } else if (StringUtils.isNotBlank(url)) {
            transport = "streamable-http".equalsIgnoreCase(text(source.get("transport")))
                    ? "STREAMABLE_HTTP" : "SSE";
            connection.put("url", url);
        } else {
            throw new RenException("MCP 服务必须包含 command 或 url");
        }
        moveSecrets(source.get("env"), "env", connection, secretValues);
        moveSecrets(source.get("headers"), "headers", connection, secretValues);
        return new ImportedConfig(transport, Map.copyOf(connection), template, List.copyOf(secretValues));
    }

    private void moveSecrets(Object raw, String group, Map<String, Object> connection, List<SecretValue> secrets) {
        if (!(raw instanceof Map<?, ?> values) || values.isEmpty()) return;
        Map<String, Object> placeholders = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = text(entry.getValue());
            if (StringUtils.isBlank(key) || value == null) continue;
            placeholders.put(key, "");
            secrets.add(new SecretValue(group + "." + key, value));
        }
        connection.put(group, Map.copyOf(placeholders));
    }

    private String secretName(String path) {
        return "import." + path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
    }

    private String normalizeName(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private record SecretValue(String path, String value) {
    }

    private record ImportedConfig(String transport, Map<String, Object> connectionConfig,
            Map<String, Object> approvedCommandTemplate, List<SecretValue> secrets) {
    }
}
