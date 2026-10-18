package zixuan.modules.companion.capability.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.util.IdUtil;
import lombok.AllArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.common.utils.JsonUtils;
import zixuan.modules.companion.capability.dao.CapabilitySecretDao;
import zixuan.modules.companion.capability.dao.DeviceToolSnapshotDao;
import zixuan.modules.companion.capability.dao.McpServerDao;
import zixuan.modules.companion.capability.dao.McpToolSnapshotDao;
import zixuan.modules.companion.capability.dto.DeviceToolSnapshotSaveDTO;
import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;
import zixuan.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import zixuan.modules.companion.capability.entity.McpServerEntity;
import zixuan.modules.companion.capability.entity.McpToolSnapshotEntity;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.service.InternalCapabilityService;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveToolVO;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.device.dao.DeviceDao;

@Service
@AllArgsConstructor
public class InternalCapabilityServiceImpl implements InternalCapabilityService {
    private final DeviceDao deviceDao;
    private final DeviceToolSnapshotDao snapshotDao;
    private final CapabilitySecretDao secretDao;
    private final McpToolSnapshotDao mcpToolDao;
    private final McpServerDao mcpServerDao;
    private final DeviceCapabilityService deviceCapabilities;
    private final CompanionModelSecretService cipher;

    @Override
    public EffectiveCapabilityBundleVO bundle(String deviceId) {
        return deviceCapabilities.effectiveBundle(deviceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDeviceTools(String deviceId, DeviceToolSnapshotSaveDTO request) {
        if (deviceDao.selectById(deviceId) == null) throw new RenException("设备不存在");
        if (request == null || request.getTools() == null || request.getTools().isEmpty()) {
            throw new RenException("设备工具快照不能为空");
        }
        Date now = new Date();
        Set<String> names = new HashSet<>();
        for (DeviceToolSnapshotSaveDTO.ToolDTO tool : request.getTools()) {
            String name = tool == null ? null : StringUtils.trimToNull(tool.getName());
            if (name == null || tool.getInputSchema() == null || !names.add(name)) {
                throw new RenException("设备工具快照无效或重复");
            }
            String schema = JsonUtils.toJsonString(canonical(tool.getInputSchema()));
            DeviceToolSnapshotEntity row = snapshotDao.selectByDeviceAndTool(deviceId, name);
            boolean create = row == null;
            if (create) {
                row = new DeviceToolSnapshotEntity();
                row.setId(IdUtil.fastSimpleUUID());
                row.setDeviceId(deviceId);
                row.setToolName(name);
                row.setCreatedAt(now);
            }
            row.setInputSchemaJson(schema);
            row.setSchemaSha256(sha256(schema));
            row.setDeviceModel(StringUtils.trimToNull(request.getDeviceModel()));
            row.setFirmwareVersion(StringUtils.trimToNull(request.getFirmwareVersion()));
            row.setAvailable(Boolean.FALSE.equals(tool.getAvailable()) ? 0 : 1);
            row.setLastSeenAt(now);
            row.setUpdatedAt(now);
            int written = create ? snapshotDao.insert(row) : snapshotDao.updateById(row);
            if (written != 1) throw new RenException("设备工具快照保存失败");
        }
    }

    @Override
    public String secret(String deviceId, String secretId) {
        EffectiveCapabilityBundleVO bundle = bundle(deviceId);
        CapabilitySecretEntity secret = secretDao.selectById(secretId);
        if (secret == null || !isReferenced(bundle, secret.getCapabilityId())) {
            throw new RenException("能力密钥不存在或设备无权访问");
        }
        return cipher.decrypt(secret.getSecretCiphertext());
    }

    private boolean isReferenced(EffectiveCapabilityBundleVO bundle, String capabilityId) {
        if (bundle == null || bundle.getTools() == null) return false;
        for (EffectiveToolVO tool : bundle.getTools().values()) {
            if (tool == null) continue;
            if ("PLUGIN".equalsIgnoreCase(tool.getType()) && capabilityId.equals(tool.getRefId())) return true;
            if ("MCP".equalsIgnoreCase(tool.getType()) && mcpCapability(tool.getRefId(), capabilityId)) return true;
        }
        return false;
    }

    private boolean mcpCapability(String snapshotId, String capabilityId) {
        McpToolSnapshotEntity tool = mcpToolDao.selectById(snapshotId);
        if (tool == null) return false;
        McpServerEntity server = mcpServerDao.selectById(tool.getMcpServerId());
        return server != null && capabilityId.equals(server.getCapabilityId());
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new TreeMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof Collection<?> source) return source.stream().map(this::canonical).toList();
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
