package zixuan.modules.companion.capability.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.LinkedHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import zixuan.common.exception.RenException;
import zixuan.common.utils.JsonUtils;
import zixuan.modules.companion.capability.dao.McpServerDao;
import zixuan.modules.companion.capability.dao.McpToolSnapshotDao;
import zixuan.modules.companion.capability.dao.CapabilitySecretDao;
import zixuan.modules.companion.capability.dao.DeviceSkillMappingDao;
import zixuan.modules.companion.capability.dto.McpSyncDTO;
import zixuan.modules.companion.capability.entity.McpServerEntity;
import zixuan.modules.companion.capability.entity.McpToolSnapshotEntity;
import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;
import zixuan.modules.companion.capability.service.CapabilityRuntimeClient;
import zixuan.modules.companion.capability.service.McpCapabilityService;
import zixuan.modules.companion.capability.vo.McpOperationVO;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;

@Service
public class McpCapabilityServiceImpl implements McpCapabilityService {
    private static final Set<String> HEALTH = Set.of("HEALTHY", "UNHEALTHY");

    private final McpServerDao serverDao;
    private final McpToolSnapshotDao toolDao;
    private final DeviceSkillMappingDao mappingDao;
    private final DeviceDao deviceDao;
    private final CompanionAuditService audit;
    private final CapabilityRuntimeClient runtime;
    private final CapabilitySecretDao secretDao;
    private final CompanionModelSecretService cipher;

    public McpCapabilityServiceImpl(McpServerDao serverDao, McpToolSnapshotDao toolDao,
            DeviceSkillMappingDao mappingDao, DeviceDao deviceDao, CompanionAuditService audit) {
        this(serverDao, toolDao, mappingDao, deviceDao, audit, null, null, null);
    }

    @Autowired
    public McpCapabilityServiceImpl(McpServerDao serverDao, McpToolSnapshotDao toolDao,
            DeviceSkillMappingDao mappingDao, DeviceDao deviceDao, CompanionAuditService audit,
            CapabilityRuntimeClient runtime, CapabilitySecretDao secretDao,
            CompanionModelSecretService cipher) {
        this.serverDao = serverDao;
        this.toolDao = toolDao;
        this.mappingDao = mappingDao;
        this.deviceDao = deviceDao;
        this.audit = audit;
        this.runtime = runtime;
        this.secretDao = secretDao;
        this.cipher = cipher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<McpToolSnapshotEntity> sync(String serverId, McpSyncDTO request) {
        McpServerEntity server = serverDao.selectById(serverId);
        if (server == null || request == null) throw new RenException("MCP 服务不存在");
        String health = normalize(request.getHealthStatus());
        if (!HEALTH.contains(health)) throw new RenException("MCP 健康状态无效");
        Date now = new Date();
        server.setHealthStatus(health);
        server.setLastError("UNHEALTHY".equals(health) ? StringUtils.trimToNull(request.getErrorClass()) : null);
        server.setLastCheckedAt(now);
        server.setUpdatedAt(now);
        if (serverDao.updateById(server) != 1) throw new RenException("MCP 健康状态保存失败");

        List<McpToolSnapshotEntity> existing = rows(toolDao.selectByMcpServerId(serverId));
        if (!"HEALTHY".equals(health)) return existing;

        Map<String, McpToolSnapshotEntity> byName = new HashMap<>();
        existing.forEach(item -> byName.put(item.getToolName(), item));
        Set<String> seen = new HashSet<>();
        List<McpToolSnapshotEntity> result = new ArrayList<>();
        boolean authorizationChanged = false;
        for (McpSyncDTO.ToolDTO discovered : request.getTools()) {
            String name = discovered == null ? null : StringUtils.trimToNull(discovered.getName());
            if (name == null || discovered.getInputSchema() == null || !seen.add(name)) {
                throw new RenException("MCP 工具同步数据无效或重复");
            }
            String schemaJson = JsonUtils.toJsonString(canonical(discovered.getInputSchema()));
            String hash = sha256(schemaJson);
            McpToolSnapshotEntity row = byName.remove(name);
            boolean create = row == null;
            if (create) {
                row = new McpToolSnapshotEntity();
                row.setId(cn.hutool.core.util.IdUtil.fastSimpleUUID());
                row.setMcpServerId(serverId);
                row.setToolName(name);
                row.setApproved(0);
                row.setStatus("DISCOVERED");
                row.setCreatedAt(now);
            } else if (!hash.equals(row.getSchemaSha256())) {
                authorizationChanged |= Integer.valueOf(1).equals(row.getApproved());
                row.setApproved(0);
                row.setStatus("DRIFTED");
            } else {
                row.setStatus(Integer.valueOf(1).equals(row.getApproved()) ? "ACTIVE" : "DISCOVERED");
            }
            row.setInputSchemaJson(schemaJson);
            row.setSchemaSha256(hash);
            row.setSyncedAt(now);
            row.setUpdatedAt(now);
            int written = create ? toolDao.insert(row) : toolDao.updateById(row);
            if (written != 1) throw new RenException("MCP 工具同步保存失败");
            result.add(row);
        }
        for (McpToolSnapshotEntity missing : byName.values()) {
            authorizationChanged |= Integer.valueOf(1).equals(missing.getApproved());
            missing.setApproved(0);
            missing.setStatus("MISSING");
            missing.setSyncedAt(now);
            missing.setUpdatedAt(now);
            if (toolDao.updateById(missing) != 1) throw new RenException("MCP 工具离线状态保存失败");
            result.add(missing);
        }
        if (authorizationChanged) bumpBoundDeviceVersions(now);
        result.sort(java.util.Comparator.comparing(McpToolSnapshotEntity::getToolName));
        return List.copyOf(result);
    }

    @Override
    public List<McpToolSnapshotEntity> list(String capabilityId) {
        McpServerEntity server = serverDao.selectByCapabilityId(capabilityId);
        if (server == null) throw new RenException("MCP 服务不存在");
        return List.copyOf(rows(toolDao.selectByMcpServerId(server.getId())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<McpToolSnapshotEntity> approve(Long operatorId, String capabilityId, List<String> approvedToolIds) {
        McpServerEntity server = serverDao.selectByCapabilityId(capabilityId);
        if (server == null) throw new RenException("MCP 服务不存在");
        Set<String> approved = new HashSet<>(approvedToolIds == null ? List.of() : approvedToolIds);
        List<McpToolSnapshotEntity> rows = rows(toolDao.selectByMcpServerId(server.getId()));
        Set<String> known = rows.stream().map(McpToolSnapshotEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(approved)) throw new RenException("MCP 工具白名单包含未知工具");
        if (rows.stream().anyMatch(row -> approved.contains(row.getId())
                && !Set.of("DISCOVERED", "ACTIVE").contains(normalize(row.getStatus())))) {
            throw new RenException("MCP 工具状态不可审批");
        }
        Date now = new Date();
        boolean changed = false;
        for (McpToolSnapshotEntity row : rows) {
            boolean enabled = approved.contains(row.getId());
            changed |= enabled != Integer.valueOf(1).equals(row.getApproved());
            row.setApproved(enabled ? 1 : 0);
            if (enabled) row.setStatus("ACTIVE");
            else if ("ACTIVE".equals(row.getStatus())) row.setStatus("DISCOVERED");
            row.setUpdatedAt(now);
            if (toolDao.updateById(row) != 1) throw new RenException("MCP 工具白名单保存失败");
        }
        if (changed) bumpBoundDeviceVersions(now);
        audit.record(operatorId, null, "mcp.tools.approve", "capability", capabilityId,
                Map.of("approvedToolCount", approved.size()));
        return List.copyOf(rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpOperationVO testConnection(Long operatorId, String capabilityId) {
        McpServerEntity server = requireCapabilityServer(capabilityId);
        CapabilityRuntimeClient.McpTestResult result = executeRuntimeTest(server);
        updateHealth(server, result);
        auditOperation(operatorId, capabilityId, "mcp.connection.test", result, 0);
        return new McpOperationVO(result.success(), result.errorClass(), List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpOperationVO syncFromRuntime(Long operatorId, String capabilityId) {
        McpServerEntity server = requireCapabilityServer(capabilityId);
        CapabilityRuntimeClient.McpTestResult result = executeRuntimeTest(server);
        if (!result.success()) {
            updateHealth(server, result);
            auditOperation(operatorId, capabilityId, "mcp.tools.sync", result, 0);
            return new McpOperationVO(false, result.errorClass(), list(capabilityId));
        }
        McpSyncDTO request = new McpSyncDTO();
        request.setHealthStatus("HEALTHY");
        request.setTools(result.tools().stream().map(this::syncTool).toList());
        List<McpToolSnapshotEntity> snapshots = sync(server.getId(), request);
        auditOperation(operatorId, capabilityId, "mcp.tools.sync", result, snapshots.size());
        return new McpOperationVO(true, null, snapshots);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindSecretReference(Long operatorId, String capabilityId, String path) {
        McpServerEntity server = serverDao.selectByCapabilityId(capabilityId);
        if (server == null) return;
        String normalizedPath = StringUtils.trimToEmpty(path);
        if (!normalizedPath.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)+")) {
            throw new RenException("MCP 密钥路径无效");
        }
        String secretName = normalizedPath.toLowerCase(Locale.ROOT);
        CapabilitySecretEntity secret = secretDao.selectByCapabilityAndName(capabilityId, secretName);
        if (secret == null) throw new RenException("MCP 密钥未配置");
        Map<String, Object> refs = new LinkedHashMap<>(parsedMap(server.getSecretRefsJson()));
        if (secret.getId().equals(refs.get(normalizedPath))) return;
        refs.put(normalizedPath, secret.getId());
        Date now = new Date();
        server.setSecretRefsJson(JsonUtils.toJsonString(canonical(refs)));
        server.setUpdatedAt(now);
        if (serverDao.updateById(server) != 1) throw new RenException("MCP 密钥引用保存失败");
        bumpBoundDeviceVersions(now);
        audit.record(operatorId, null, "mcp.secret.bind", "capability", capabilityId,
                Map.of("path", normalizedPath));
    }

    private CapabilityRuntimeClient.McpTestResult executeRuntimeTest(McpServerEntity server) {
        if (runtime == null || secretDao == null || cipher == null) {
            return new CapabilityRuntimeClient.McpTestResult(false, "RuntimeUnavailable", List.of());
        }
        try {
            return runtime.testMcp(runtimeRequest(server));
        } catch (RuntimeException exception) {
            return new CapabilityRuntimeClient.McpTestResult(
                    false, exception.getClass().getSimpleName(), List.of());
        }
    }

    private CapabilityRuntimeClient.McpTestRequest runtimeRequest(McpServerEntity server) {
        Map<String, Object> connection = mutableMap(server.getConnectionConfigJson());
        Map<String, Object> refs = parsedMap(server.getSecretRefsJson());
        for (Map.Entry<String, Object> entry : refs.entrySet()) {
            String secretId = entry.getValue() instanceof String value ? StringUtils.trimToNull(value) : null;
            CapabilitySecretEntity secret = secretId == null ? null : secretDao.selectById(secretId);
            if (secret == null || !server.getCapabilityId().equals(secret.getCapabilityId())) {
                throw new RenException("MCP 密钥未配置");
            }
            setPath(connection, entry.getKey(), cipher.decrypt(secret.getSecretCiphertext()));
        }
        return new CapabilityRuntimeClient.McpTestRequest(
                normalize(server.getTransport()), connection, parsedNullableMap(server.getApprovedCommandTemplateJson()));
    }

    private McpSyncDTO.ToolDTO syncTool(CapabilityRuntimeClient.McpTool item) {
        McpSyncDTO.ToolDTO result = new McpSyncDTO.ToolDTO();
        result.setName(item.name());
        result.setInputSchema(item.inputSchema());
        return result;
    }

    private McpServerEntity requireCapabilityServer(String capabilityId) {
        McpServerEntity server = serverDao.selectByCapabilityId(capabilityId);
        if (server == null) throw new RenException("MCP 服务不存在");
        return server;
    }

    private void updateHealth(McpServerEntity server, CapabilityRuntimeClient.McpTestResult result) {
        Date now = new Date();
        server.setHealthStatus(result.success() ? "HEALTHY" : "UNHEALTHY");
        server.setLastError(result.success() ? null : StringUtils.defaultIfBlank(result.errorClass(), "RuntimeError"));
        server.setLastCheckedAt(now);
        server.setUpdatedAt(now);
        if (serverDao.updateById(server) != 1) throw new RenException("MCP 健康状态保存失败");
    }

    private void auditOperation(Long operatorId, String capabilityId, String action,
            CapabilityRuntimeClient.McpTestResult result, int toolCount) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("success", result.success());
        detail.put("toolCount", toolCount);
        if (StringUtils.isNotBlank(result.errorClass())) detail.put("errorClass", result.errorClass());
        audit.record(operatorId, null, action, "capability", capabilityId, detail);
    }

    private Map<String, Object> parsedMap(String value) {
        if (StringUtils.isBlank(value)) return Map.of();
        Map<String, Object> parsed = JsonUtils.parseMap(value);
        if (parsed == null) return Map.of();
        return parsed;
    }

    private Map<String, Object> parsedNullableMap(String value) {
        return StringUtils.isBlank(value) ? null : parsedMap(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(String value) {
        Object copied = JsonUtils.parseObject(JsonUtils.toJsonString(parsedMap(value)), Object.class);
        if (!(copied instanceof Map<?, ?> source)) throw new RenException("MCP 连接配置无效");
        return (Map<String, Object>) source;
    }

    @SuppressWarnings("unchecked")
    private void setPath(Map<String, Object> target, String path, String value) {
        String[] parts = StringUtils.split(path, '.');
        if (parts == null || parts.length == 0) throw new RenException("MCP 密钥引用路径无效");
        Map<String, Object> current = target;
        for (int index = 0; index < parts.length - 1; index++) {
            Object child = current.get(parts[index]);
            if (child == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(parts[index], created);
                current = created;
            } else if (child instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                throw new RenException("MCP 密钥引用路径无效");
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    private void bumpBoundDeviceVersions(Date now) {
        for (String deviceId : rows(mappingDao.selectEnabledDeviceIds())) {
            DeviceEntity device = deviceDao.selectByIdForUpdate(deviceId);
            if (device == null) continue;
            long current = device.getCapabilityConfigVersion() == null ? 0L : device.getCapabilityConfigVersion();
            device.setCapabilityConfigVersion(current + 1);
            device.setUpdateDate(now);
            if (deviceDao.updateById(device) != 1) throw new RenException("设备能力版本更新失败");
        }
    }

    public static String schemaHash(Map<String, Object> schema) {
        return sha256(JsonUtils.toJsonString(canonical(schema)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new TreeMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof Collection<?> source) return source.stream().map(McpCapabilityServiceImpl::canonical).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String normalize(String value) {
        return StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private <T> List<T> rows(List<T> value) {
        return value == null ? List.of() : value;
    }
}
