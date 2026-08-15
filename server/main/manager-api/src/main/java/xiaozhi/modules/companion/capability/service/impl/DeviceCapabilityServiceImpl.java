package xiaozhi.modules.companion.capability.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.vo.DeviceSkillBindingVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveSkillVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveToolVO;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

@Service
@RequiredArgsConstructor
public class DeviceCapabilityServiceImpl implements DeviceCapabilityService {
    private final DeviceDao deviceDao;
    private final DeviceSkillMappingDao mappingDao;
    private final CapabilityDao capabilityDao;
    private final CapabilityVersionDao versionDao;
    private final CompanionAuditService audit;
    private McpToolSnapshotDao mcpToolDao;
    private McpServerDao mcpServerDao;

    @Autowired
    public void setMcpRuntimeDaos(McpToolSnapshotDao mcpToolDao, McpServerDao mcpServerDao) {
        this.mcpToolDao = mcpToolDao;
        this.mcpServerDao = mcpServerDao;
    }

    @Override
    public List<DeviceSkillBindingVO> list(Long callerId, String deviceId, boolean superAdmin) {
        requireAccess(callerId, deviceId, superAdmin);
        return rows(mappingDao.selectByDeviceId(deviceId)).stream().map(this::toBindingVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DeviceSkillBindingVO> save(Long callerId, String deviceId, List<DeviceSkillBindingDTO> bindings,
            boolean superAdmin) {
        DeviceEntity device = superAdmin ? deviceDao.selectByIdForUpdate(deviceId)
                : deviceDao.selectOwnedByIdForUpdate(deviceId, callerId);
        if (device == null) throw new RenException("设备不存在或无权访问");
        List<DeviceSkillBindingDTO> requested = bindings == null ? List.of() : bindings;
        Set<String> skillIds = new HashSet<>();
        List<ResolvedBinding> resolved = new ArrayList<>();
        for (DeviceSkillBindingDTO binding : requested) {
            if (binding == null || StringUtils.isBlank(binding.getSkillId()) || !skillIds.add(binding.getSkillId())) {
                throw new RenException("设备 Skill 绑定无效或重复");
            }
            resolved.add(resolve(binding));
        }

        long configVersion = (device.getCapabilityConfigVersion() == null ? 0L : device.getCapabilityConfigVersion()) + 1;
        Date now = new Date();
        mappingDao.deleteByDeviceId(deviceId);
        List<DeviceSkillMappingEntity> saved = new ArrayList<>();
        for (ResolvedBinding item : resolved) {
            DeviceSkillMappingEntity row = new DeviceSkillMappingEntity();
            row.setId(IdUtil.getSnowflakeNextId());
            row.setDeviceId(deviceId);
            row.setSkillId(item.capability().getId());
            row.setVersionMode(normalize(item.request().getVersionMode()));
            row.setFixedVersion("FIXED".equals(row.getVersionMode()) ? item.version().getVersionNo() : null);
            row.setEnabled(Boolean.FALSE.equals(item.request().getEnabled()) ? 0 : 1);
            row.setOverrideJson(item.overrides().isEmpty() ? null : JsonUtils.toJsonString(canonical(item.overrides())));
            row.setTriggerPriority(item.request().getTriggerPriority() == null ? 0 : item.request().getTriggerPriority());
            row.setConfigVersion(configVersion);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            if (mappingDao.insert(row) != 1) throw new RenException("设备 Skill 绑定保存失败");
            saved.add(row);
        }
        device.setCapabilityConfigVersion(configVersion);
        device.setUpdater(callerId);
        device.setUpdateDate(now);
        if (deviceDao.updateById(device) != 1) throw new RenException("设备能力版本更新失败");
        audit.record(callerId, device.getUserId(), "device.capability.save", "device", deviceId,
                Map.of("bindingCount", saved.size(), "configVersion", configVersion));
        return saved.stream().map(this::toBindingVO).toList();
    }

    @Override
    public EffectiveCapabilityBundleVO effectiveBundle(String deviceId) {
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null) throw new RenException("设备不存在");
        EffectiveCapabilityBundleVO bundle = new EffectiveCapabilityBundleVO();
        bundle.setDeviceId(deviceId);
        bundle.setConfigVersion(device.getCapabilityConfigVersion() == null ? 0L : device.getCapabilityConfigVersion());
        List<EffectiveSkillVO> skills = new ArrayList<>();
        Map<String, EffectiveToolVO> tools = new LinkedHashMap<>();
        for (DeviceSkillMappingEntity mapping : rows(mappingDao.selectEnabledByDevice(deviceId))) {
            ResolvedPublished published = resolve(mapping);
            Map<String, Object> content = published.content();
            Map<String, Object> overrides = map(parse(mapping.getOverrideJson()));
            Map<String, Object> defaults = mergedDefaults(content, overrides);

            EffectiveSkillVO skill = new EffectiveSkillVO();
            skill.setId(mapping.getSkillId());
            skill.setVersion(published.version().getVersionNo());
            skill.setName(text(content.get("name")));
            skill.setDescription(nullableText(content.get("description")));
            skill.setExecutionPrompt(text(content.get("executionPrompt")));
            skill.setSemanticThreshold(decimal(content.get("semanticThreshold")));
            skill.setResponseMode(text(content.get("responseMode")));
            skill.setTimeoutMs(integer(content.get("timeoutMs")));
            skill.setFailureMessage(nullableText(content.get("failureMessage")));
            skill.setBindingPriority(mapping.getTriggerPriority() == null ? 0 : mapping.getTriggerPriority());
            skill.setTriggers(maps(content.get("triggers")));
            skill.setDefaults(defaults);

            List<String> toolNames = new ArrayList<>();
            for (Map<String, Object> tool : maps(content.get("tools"))) {
                String toolName = text(tool.get("toolName"));
                if (toolName.isBlank()) continue;
                toolNames.add(toolName);
                EffectiveToolVO effective = new EffectiveToolVO();
                effective.setName(toolName);
                effective.setType(text(tool.get("toolType")));
                effective.setRefId(text(tool.get("toolRefId")));
                effective.setAlias(nullableText(tool.get("alias")));
                effective.setPurpose(nullableText(tool.get("purpose")));
                effective.setRequired(Boolean.TRUE.equals(tool.get("required")));
                Map<String, Object> toolDefaults = new LinkedHashMap<>(map(tool.get("defaultParams")));
                defaults.forEach((key, value) -> {
                    if (toolDefaults.containsKey(key) || map(content.get("defaults")).containsKey(key)) {
                        toolDefaults.put(key, value);
                    }
                });
                effective.setDefaults(Map.copyOf(toolDefaults));
                if ("MCP".equalsIgnoreCase(effective.getType())) {
                    effective.setRuntime(mcpRuntime(effective.getRefId()));
                }
                tools.put(toolName, effective);
            }
            skill.setToolNames(List.copyOf(toolNames));
            skills.add(skill);
        }
        bundle.setSkills(List.copyOf(skills));
        bundle.setTools(tools);
        return bundle;
    }

    private ResolvedBinding resolve(DeviceSkillBindingDTO binding) {
        String mode = normalize(binding.getVersionMode());
        if (!Set.of("LATEST", "FIXED").contains(mode)) throw new RenException("Skill 版本策略无效");
        CapabilityEntity capability = capabilityDao.selectById(binding.getSkillId());
        if (capability == null || !"SKILL".equals(capability.getType())
                || !"PUBLISHED".equals(capability.getStatus()) || capability.getPublishedVersion() == null) {
            throw new RenException("Skill 未发布或不存在");
        }
        Integer versionNo = "FIXED".equals(mode) ? binding.getFixedVersion() : capability.getPublishedVersion();
        if (versionNo == null || versionNo < 1) throw new RenException("Skill 固定版本无效");
        CapabilityVersionEntity version = versionDao.selectVersion(capability.getId(), versionNo);
        if (version == null) throw new RenException("Skill 发布版本不存在");
        Map<String, Object> content = JsonUtils.parseMap(version.getContentJson());
        Map<String, Object> overrides = binding.getOverrides() == null ? Map.of() : binding.getOverrides();
        Set<String> allowed = overrideKeys(content);
        if (!allowed.containsAll(overrides.keySet())) {
            Set<String> rejected = new LinkedHashSet<>(overrides.keySet());
            rejected.removeAll(allowed);
            throw new RenException("设备覆盖字段不允许: " + String.join(",", rejected));
        }
        return new ResolvedBinding(binding, capability, version, new LinkedHashMap<>(overrides));
    }

    private ResolvedPublished resolve(DeviceSkillMappingEntity mapping) {
        CapabilityEntity capability = capabilityDao.selectById(mapping.getSkillId());
        if (capability == null || !"SKILL".equals(capability.getType()) || capability.getPublishedVersion() == null) {
            throw new RenException("设备绑定的 Skill 不存在");
        }
        Integer versionNo = "FIXED".equalsIgnoreCase(mapping.getVersionMode())
                ? mapping.getFixedVersion() : capability.getPublishedVersion();
        CapabilityVersionEntity version = versionDao.selectVersion(mapping.getSkillId(), versionNo);
        if (version == null) throw new RenException("设备绑定的 Skill 版本不存在");
        return new ResolvedPublished(version, JsonUtils.parseMap(version.getContentJson()));
    }

    private DeviceSkillBindingVO toBindingVO(DeviceSkillMappingEntity row) {
        ResolvedPublished published = resolve(row);
        CapabilityEntity capability = capabilityDao.selectById(row.getSkillId());
        DeviceSkillBindingVO vo = new DeviceSkillBindingVO();
        vo.setSkillId(row.getSkillId());
        vo.setSkillName(capability == null ? null : capability.getName());
        vo.setVersionMode(row.getVersionMode());
        vo.setFixedVersion(row.getFixedVersion());
        vo.setResolvedVersion(published.version().getVersionNo());
        vo.setEnabled(!Integer.valueOf(0).equals(row.getEnabled()));
        vo.setOverrides(map(parse(row.getOverrideJson())));
        vo.setTriggerPriority(row.getTriggerPriority());
        vo.setConfigVersion(row.getConfigVersion());
        return vo;
    }

    private DeviceEntity requireAccess(Long callerId, String deviceId, boolean superAdmin) {
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null || !superAdmin && !java.util.Objects.equals(callerId, device.getUserId())) {
            throw new RenException("设备不存在或无权访问");
        }
        return device;
    }

    private Set<String> overrideKeys(Map<String, Object> content) {
        Set<String> result = new LinkedHashSet<>();
        Object declared = content.get("overridableFields");
        if (declared instanceof Collection<?> values) values.forEach(value -> result.add(String.valueOf(value)));
        result.addAll(map(content.get("defaults")).keySet());
        maps(content.get("tools")).forEach(tool -> result.addAll(map(tool.get("defaultParams")).keySet()));
        return result;
    }

    private Map<String, Object> mcpRuntime(String snapshotId) {
        if (mcpToolDao == null || mcpServerDao == null) return Map.of();
        McpToolSnapshotEntity snapshot = mcpToolDao.selectById(snapshotId);
        if (snapshot == null || !Integer.valueOf(1).equals(snapshot.getApproved())
                || !"ACTIVE".equalsIgnoreCase(snapshot.getStatus())) return Map.of();
        McpServerEntity server = mcpServerDao.selectById(snapshot.getMcpServerId());
        if (server == null) return Map.of();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("serverId", server.getId());
        runtime.put("transport", server.getTransport());
        runtime.put("connectionConfig", map(parse(server.getConnectionConfigJson())));
        runtime.put("secretRefs", map(parse(server.getSecretRefsJson())));
        runtime.put("approvedCommandTemplate", parse(server.getApprovedCommandTemplateJson()));
        runtime.put("inputSchema", map(parse(snapshot.getInputSchemaJson())));
        runtime.put("schemaSha256", snapshot.getSchemaSha256());
        return runtime;
    }

    private Map<String, Object> mergedDefaults(Map<String, Object> content, Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();
        maps(content.get("tools")).forEach(tool -> result.putAll(map(tool.get("defaultParams"))));
        result.putAll(map(content.get("defaults")));
        result.putAll(overrides);
        return Map.copyOf(result);
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

    private Object parse(String value) {
        return StringUtils.isBlank(value) ? null : JsonUtils.parseObject(value, Object.class);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> source)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        source.forEach(item -> {
            if (item instanceof Map<?, ?>) result.add(map(item));
        });
        return List.copyOf(result);
    }

    private List<DeviceSkillMappingEntity> rows(List<DeviceSkillMappingEntity> value) {
        return value == null ? List.of() : value;
    }

    private String normalize(String value) {
        return StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal
                : value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    private record ResolvedBinding(DeviceSkillBindingDTO request, CapabilityEntity capability,
            CapabilityVersionEntity version, Map<String, Object> overrides) {
    }

    private record ResolvedPublished(CapabilityVersionEntity version, Map<String, Object> content) {
    }
}
