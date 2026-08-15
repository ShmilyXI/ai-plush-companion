package xiaozhi.modules.companion.capability.service.impl;

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

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dto.McpSyncDTO;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.service.McpCapabilityService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

@Service
@AllArgsConstructor
public class McpCapabilityServiceImpl implements McpCapabilityService {
    private static final Set<String> HEALTH = Set.of("HEALTHY", "UNHEALTHY");

    private final McpServerDao serverDao;
    private final McpToolSnapshotDao toolDao;
    private final DeviceSkillMappingDao mappingDao;
    private final DeviceDao deviceDao;
    private final CompanionAuditService audit;

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
