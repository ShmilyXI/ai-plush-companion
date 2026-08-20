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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.dao.CapabilitySecretDao;
import xiaozhi.modules.companion.capability.dao.SkillPackageDao;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.CapabilitySecretEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.vo.DeviceSkillBindingVO;
import xiaozhi.modules.companion.capability.vo.DeviceSkillCatalogVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO;
import xiaozhi.modules.companion.capability.vo.CapabilityParityVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveSkillVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveToolVO;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
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
    private DeviceToolSnapshotDao deviceToolDao;
    private PluginDefinitionDao pluginDao;
    private CapabilitySecretDao secretDao;
    private SkillPackageDao skillPackageDao;
    private AgentMcpAccessPointService agentMcpAccessPointService;
    private AgentDao agentDao;
    private AgentVersionSkillBindingDao agentVersionSkillBindingDao;
    @Value("${companion.capability.agent-projection-enabled:true}")
    private boolean agentProjectionEnabled = true;

    @Autowired
    public void setMcpRuntimeDaos(McpToolSnapshotDao mcpToolDao, McpServerDao mcpServerDao) {
        this.mcpToolDao = mcpToolDao;
        this.mcpServerDao = mcpServerDao;
    }

    @Autowired
    public void setDeviceToolSnapshotDao(DeviceToolSnapshotDao deviceToolDao) {
        this.deviceToolDao = deviceToolDao;
    }

    @Autowired
    public void setPluginSecretDaos(PluginDefinitionDao pluginDao, CapabilitySecretDao secretDao) {
        this.pluginDao = pluginDao;
        this.secretDao = secretDao;
    }

    @Autowired
    public void setSkillPackageDao(SkillPackageDao skillPackageDao) {
        this.skillPackageDao = skillPackageDao;
    }

    @Autowired(required = false)
    public void setAgentMcpAccessPointService(AgentMcpAccessPointService service) {
        this.agentMcpAccessPointService = service;
    }

    @Autowired(required = false)
    public void setAgentDao(AgentDao agentDao) {
        this.agentDao = agentDao;
    }

    @Autowired(required = false)
    public void setAgentVersionSkillBindingDao(AgentVersionSkillBindingDao dao) {
        this.agentVersionSkillBindingDao = dao;
    }

    @Autowired(required = false)
    public void setAgentProjectionEnabled(Boolean enabled) {
        if (enabled != null) this.agentProjectionEnabled = enabled;
    }

    @Override
    public List<DeviceSkillBindingVO> list(Long callerId, String deviceId, boolean superAdmin) {
        requireAccess(callerId, deviceId, superAdmin);
        return rows(mappingDao.selectByDeviceId(deviceId)).stream().map(this::toBindingVO).toList();
    }

    @Override
    public List<DeviceSkillCatalogVO> catalog(Long callerId, String deviceId, boolean superAdmin) {
        requireAccess(callerId, deviceId, superAdmin);
        QueryWrapper<CapabilityEntity> query = new QueryWrapper<>();
        query.eq("type", "SKILL").eq("status", "PUBLISHED").eq("deleted", 0).orderByAsc("name");
        List<CapabilityEntity> skills = rows(capabilityDao.selectList(query));
        List<DeviceSkillCatalogVO> result = new ArrayList<>();
        for (CapabilityEntity capability : skills) {
            if (capability.getPublishedVersion() == null) continue;
            CapabilityVersionEntity published = versionDao.selectVersion(
                    capability.getId(), capability.getPublishedVersion());
            if (published == null) continue;
            Map<String, Object> content = JsonUtils.parseMap(published.getContentJson());
            DeviceSkillCatalogVO item = new DeviceSkillCatalogVO();
            item.setSkillId(capability.getId());
            item.setName(StringUtils.defaultIfBlank(text(content.get("name")), capability.getName()));
            item.setDescription(StringUtils.defaultIfBlank(nullableText(content.get("description")), capability.getDescription()));
            item.setPublishedVersion(capability.getPublishedVersion());
            SkillPackageEntity packageRow = skillPackageDao == null ? null
                    : skillPackageDao.selectByVersion(capability.getId(), published.getVersionNo());
            if (packageRow != null) {
                item.setPackageVersion(packageRow.getVersionNo());
                item.setPackageSha256(packageRow.getPackageSha256());
                item.setPackageSource(packageRow.getSourceType());
            }
            List<Integer> versions = rows(versionDao.selectList(new QueryWrapper<CapabilityVersionEntity>()
                    .eq("capability_id", capability.getId()).orderByAsc("version_no"))).stream()
                    .filter(version -> capability.getId().equals(version.getCapabilityId()))
                    .map(CapabilityVersionEntity::getVersionNo).distinct().sorted().toList();
            item.setVersions(versions.isEmpty() ? List.of(capability.getPublishedVersion()) : versions);
            item.setOverridableFields(List.copyOf(overrideKeys(content)));
            item.setDefaults(mergedDefaults(content, Map.of()));
            String unavailable = packageIntegrityReason(published, packageRow);
            if (unavailable == null) unavailable = unavailableReason(deviceId, content);
            item.setAvailable(unavailable == null);
            item.setUnavailableReason(unavailable);
            result.add(item);
        }
        return List.copyOf(result);
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
        if (agentProjectionEnabled && agentDao != null && StringUtils.isNotBlank(device.getAgentId())) {
            AgentEntity agent = agentDao.selectById(device.getAgentId());
            if (agent != null) {
                bundle.setAgentId(agent.getId());
                bundle.setAgentVersionNo(agent.getActiveVersionNo());
            }
        }
        List<EffectiveSkillVO> skills = new ArrayList<>();
        Map<String, EffectiveToolVO> tools = new LinkedHashMap<>();
        Map<String, RoleMcpToolCatalog> roleMcpCatalogs = new LinkedHashMap<>();
        for (DeviceSkillMappingEntity mapping : effectiveMappings(device)) {
            CapabilityEntity boundSkill = capabilityDao.selectById(mapping.getSkillId());
            if (boundSkill == null || !"PUBLISHED".equals(boundSkill.getStatus())) continue;
            ResolvedPublished published = resolve(mapping);
            Map<String, Object> content = published.content();
            SkillPackageEntity packageRow = skillPackageDao == null ? null
                    : skillPackageDao.selectByVersion(mapping.getSkillId(), published.version().getVersionNo());
            if (packageIntegrityReason(published.version(), packageRow) != null) continue;
            List<Map<String, Object>> declaredTools = maps(content.get("tools"));
            if (unavailableReason(deviceId, content, roleMcpCatalogs) != null) continue;
            List<Map<String, Object>> availableTools = availableTools(deviceId, declaredTools, roleMcpCatalogs);
            if (hasConflictingToolName(tools, availableTools)) continue;
            Map<String, Object> overrides = map(parse(mapping.getOverrideJson()));
            Map<String, Object> defaults = mergedDefaults(content, overrides, availableTools);

            EffectiveSkillVO skill = new EffectiveSkillVO();
            skill.setId(mapping.getSkillId());
            skill.setVersion(published.version().getVersionNo());
            skill.setPackageVersion(packageRow == null ? integer(content.get("packageVersion"))
                    : packageRow.getVersionNo());
            skill.setPackageSha256(packageRow == null ? nullableText(content.get("packageSha256"))
                    : packageRow.getPackageSha256());
            skill.setName(text(content.get("name")));
            skill.setDescription(nullableText(content.get("description")));
            skill.setExecutionPrompt(packageRow == null ? text(content.get("executionPrompt"))
                    : packageRow.getSkillMarkdown());
            skill.setSemanticThreshold(decimal(content.get("semanticThreshold")));
            skill.setResponseMode(text(content.get("responseMode")));
            skill.setTimeoutMs(integer(content.get("timeoutMs")));
            skill.setFailureMessage(nullableText(content.get("failureMessage")));
            skill.setBindingPriority(mapping.getTriggerPriority() == null ? 0 : mapping.getTriggerPriority());
            skill.setTriggers(maps(content.get("triggers")));
            skill.setDefaults(defaults);

            List<String> toolNames = new ArrayList<>();
            for (Map<String, Object> tool : availableTools) {
                String toolName = text(tool.get("toolName"));
                if (toolName.isBlank()) continue;
                toolNames.add(toolName);
                if (tools.containsKey(toolName)) continue;
                EffectiveToolVO effective = new EffectiveToolVO();
                effective.setName(toolName);
                effective.setType(text(tool.get("toolType")));
                effective.setRefId(text(tool.get("toolRefId")));
                effective.setAlias(nullableText(tool.get("alias")));
                effective.setPurpose(nullableText(tool.get("purpose")));
                effective.setRequired(requiredTool(tool));
                Map<String, Object> toolDefaults = new LinkedHashMap<>(map(tool.get("defaultParams")));
                defaults.forEach((key, value) -> {
                    if (toolDefaults.containsKey(key) || map(content.get("defaults")).containsKey(key)) {
                        toolDefaults.put(key, value);
                    }
                });
                if ("PLUGIN".equalsIgnoreCase(effective.getType())) {
                    addPluginSecretRefs(effective.getRefId(), toolDefaults);
                }
                effective.setDefaults(Map.copyOf(toolDefaults));
                if ("MCP".equalsIgnoreCase(effective.getType())) {
                    effective.setRuntime(mcpRuntime(effective.getRefId()));
                } else if ("ROLE_MCP".equalsIgnoreCase(effective.getType())) {
                    effective.setRuntime(roleMcpRuntime(effective.getRefId()));
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

    @Override
    public CapabilityParityVO parity(String agentId) {
        CapabilityParityVO result = new CapabilityParityVO();
        if (deviceDao == null || agentDao == null || agentVersionSkillBindingDao == null) return result;
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null || agent.getActiveVersionNo() == null) return result;
        List<String> mismatches = new ArrayList<>();
        for (DeviceEntity device : deviceDao.selectByAgentId(agentId)) {
            result.setCheckedDevices(result.getCheckedDevices() + 1);
            Set<String> legacy = rows(mappingDao.selectEnabledByDevice(device.getId())).stream()
                    .map(DeviceSkillMappingEntity::getSkillId).collect(java.util.stream.Collectors.toSet());
            Set<String> projected = rows(agentVersionSkillBindingDao.selectEnabledByAgentVersion(agentId,
                    agent.getActiveVersionNo())).stream().filter(row -> Integer.valueOf(1).equals(row.getEnabled()))
                    .map(AgentVersionSkillBindingEntity::getSkillId).collect(java.util.stream.Collectors.toSet());
            if (!legacy.equals(projected)) mismatches.add(device.getId());
        }
        result.setMismatches(List.copyOf(mismatches));
        result.setMismatchedDevices(mismatches.size());
        return result;
    }

    private List<DeviceSkillMappingEntity> effectiveMappings(DeviceEntity device) {
        if (agentVersionSkillBindingDao != null && agentDao != null
                && StringUtils.isNotBlank(device.getAgentId())) {
            AgentEntity agent = agentDao.selectById(device.getAgentId());
            if (agent != null && agent.getActiveVersionNo() != null) {
                List<AgentVersionSkillBindingEntity> bindings = agentVersionSkillBindingDao
                        .selectEnabledByAgentVersion(agent.getId(), agent.getActiveVersionNo());
                if (bindings != null && !bindings.isEmpty()) {
                    return bindings.stream().map(this::toLegacyShape).toList();
                }
            }
        }
        return rows(mappingDao.selectEnabledByDevice(device.getId()));
    }

    private DeviceSkillMappingEntity toLegacyShape(AgentVersionSkillBindingEntity source) {
        DeviceSkillMappingEntity target = new DeviceSkillMappingEntity();
        target.setId(source.getId());
        target.setDeviceId(null);
        target.setSkillId(source.getSkillId());
        target.setVersionMode(source.getVersionMode());
        target.setFixedVersion(source.getFixedVersion());
        target.setOverrideJson(source.getOverrideJson());
        target.setTriggerPriority(source.getTriggerPriority());
        target.setEnabled(source.getEnabled());
        return target;
    }

    private boolean hasConflictingToolName(Map<String, EffectiveToolVO> existingTools,
            List<Map<String, Object>> candidateTools) {
        for (Map<String, Object> candidate : candidateTools) {
            EffectiveToolVO existing = existingTools.get(text(candidate.get("toolName")));
            if (existing == null) continue;
            if (!normalize(existing.getType()).equals(normalize(text(candidate.get("toolType"))))
                    || !StringUtils.equals(existing.getRefId(), text(candidate.get("toolRefId")))) {
                return true;
            }
        }
        return false;
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
        SkillPackageEntity packageRow = skillPackageDao == null ? null
                : skillPackageDao.selectByVersion(capability.getId(), versionNo);
        String packageReason = packageIntegrityReason(version, packageRow);
        if (packageReason != null) throw new RenException(packageReason);
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

    private String unavailableReason(String deviceId, Map<String, Object> content) {
        return unavailableReason(deviceId, content, new LinkedHashMap<>());
    }

    private String unavailableReason(String deviceId, Map<String, Object> content,
            Map<String, RoleMcpToolCatalog> roleMcpCatalogs) {
        String deviceReason = deviceRequirementReason(deviceId, content.get("deviceRequirements"));
        if (deviceReason != null) return deviceReason;
        List<Map<String, Object>> tools = maps(content.get("tools"));
        for (Map<String, Object> tool : tools) {
            if (!requiredTool(tool)) continue;
            String reason = toolUnavailableReason(deviceId, tool, roleMcpCatalogs);
            if (reason != null) return reason;
        }
        return null;
    }

    private String packageIntegrityReason(CapabilityVersionEntity version, SkillPackageEntity packageRow) {
        if (packageRow == null) return null;
        if (!Integer.valueOf(1).equals(packageRow.getPublished())) return "Skill 分发包未发布";
        if (!"VALID".equals(packageRow.getValidationStatus())) return "Skill 分发包校验状态无效";
        if (StringUtils.isBlank(packageRow.getPackageSha256())
                || !packageRow.getPackageSha256().equals(version.getContentSha256())) {
            return "Skill 分发包投影摘要不一致";
        }
        return null;
    }

    private String deviceRequirementReason(String deviceId, Object rawRequirements) {
        if (rawRequirements == null) return null;
        DeviceEntity device = deviceDao.selectById(deviceId);
        DeviceToolSnapshotEntity snapshot = deviceToolDao == null ? null
                : deviceToolDao.selectLatestByDeviceId(deviceId);
        String model = snapshot == null ? nullableText(device == null ? null : device.getBoard())
                : StringUtils.defaultIfBlank(snapshot.getDeviceModel(), device == null ? null : device.getBoard());
        String firmware = snapshot == null ? null : snapshot.getFirmwareVersion();
        if (rawRequirements instanceof Collection<?> requirements) {
            for (Object raw : requirements) {
                if (!(raw instanceof Map<?, ?> requirement)) return "设备要求配置无效";
                String type = normalize(text(requirement.get("type")));
                String value = StringUtils.trimToNull(text(requirement.get("value")));
                if (value == null) return "设备要求配置无效";
                String reason = matchesDeviceRequirement(deviceId, type, value, model, firmware);
                if (reason != null) return reason;
            }
            return null;
        }
        if (!(rawRequirements instanceof Map<?, ?> requirements)) return "设备要求配置无效";
        Object models = requirements.containsKey("models") ? requirements.get("models")
                : requirements.get("deviceModels");
        if (models != null) {
            if (!(models instanceof Collection<?> values)) return "设备型号要求配置无效";
            if (!values.isEmpty() && (model == null || values.stream().noneMatch(value -> model.equalsIgnoreCase(text(value))))) {
                return model == null ? "设备型号未知" : "设备型号不满足 Skill 要求";
            }
        }
        String min = StringUtils.trimToNull(text(requirements.get("minFirmwareVersion")));
        String max = StringUtils.trimToNull(text(requirements.get("maxFirmwareVersion")));
        if (min != null || max != null) {
            if (firmware == null) return "设备固件版本未知";
            if (min != null && compareVersions(firmware, min) < 0) return "设备固件版本低于 Skill 要求";
            if (max != null && compareVersions(firmware, max) > 0) return "设备固件版本高于 Skill 要求";
        }
        Object requiredTools = requirements.get("requiredTools");
        if (requiredTools != null) {
            if (!(requiredTools instanceof Collection<?> values)) return "必需设备工具要求配置无效";
            for (Object value : values) {
                String toolName = StringUtils.trimToNull(text(value));
                if (toolName == null || toolUnavailableReason(deviceId, Map.of(
                        "toolType", "DEVICE_TOOL", "toolName", toolName)) != null) {
                    return "设备缺少必需工具 " + text(value);
                }
            }
        }
        return null;
    }

    private String matchesDeviceRequirement(String deviceId, String type, String value,
            String model, String firmware) {
        return switch (type) {
            case "DEVICE_MODEL" -> model == null ? "设备型号未知"
                    : model.equalsIgnoreCase(value) ? null : "设备型号不满足 Skill 要求";
            case "MIN_FIRMWARE_VERSION" -> firmware == null ? "设备固件版本未知"
                    : compareVersions(firmware, value) < 0 ? "设备固件版本低于 Skill 要求" : null;
            case "MAX_FIRMWARE_VERSION" -> firmware == null ? "设备固件版本未知"
                    : compareVersions(firmware, value) > 0 ? "设备固件版本高于 Skill 要求" : null;
            case "REQUIRED_TOOL" -> toolUnavailableReason(deviceId, Map.of(
                    "toolType", "DEVICE_TOOL", "toolName", value)) == null ? null : "设备缺少必需工具 " + value;
            default -> "设备要求配置无效";
        };
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftPart = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightPart = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            if (leftPart != rightPart) return Integer.compare(leftPart, rightPart);
        }
        return 0;
    }

    private int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private List<Map<String, Object>> availableTools(String deviceId, List<Map<String, Object>> tools,
            Map<String, RoleMcpToolCatalog> roleMcpCatalogs) {
        return tools.stream()
                .filter(tool -> toolUnavailableReason(deviceId, tool, roleMcpCatalogs) == null)
                .toList();
    }

    private boolean requiredTool(Map<String, Object> tool) {
        return !tool.containsKey("required") || Boolean.TRUE.equals(tool.get("required"));
    }

    private String toolUnavailableReason(String deviceId, Map<String, Object> tool) {
        return toolUnavailableReason(deviceId, tool, new LinkedHashMap<>());
    }

    private String toolUnavailableReason(String deviceId, Map<String, Object> tool,
            Map<String, RoleMcpToolCatalog> roleMcpCatalogs) {
        String type = normalize(text(tool.get("toolType")));
        String name = text(tool.get("toolName"));
        if ("DEVICE_TOOL".equals(type)) {
            if (deviceToolDao == null) return "设备工具状态未知";
            var snapshot = deviceToolDao.selectByDeviceAndTool(deviceId, name);
            if (snapshot == null) return "设备未上报工具 " + name;
            if (!Integer.valueOf(1).equals(snapshot.getAvailable())) return "设备工具 " + name + " 当前不可用";
        }
        if ("PLUGIN".equals(type)) {
            CapabilityEntity plugin = capabilityDao.selectById(text(tool.get("toolRefId")));
            if (plugin == null || !"PLUGIN".equals(plugin.getType())
                    || !"PUBLISHED".equals(plugin.getStatus())) {
                return "Plugin 工具 " + name + " 未发布或不可用";
            }
        }
        if ("MCP".equals(type)) {
            if (mcpToolDao == null) return "MCP 工具状态未知";
            McpToolSnapshotEntity snapshot = mcpToolDao.selectById(text(tool.get("toolRefId")));
            if (snapshot == null || !Integer.valueOf(1).equals(snapshot.getApproved())
                    || !"ACTIVE".equalsIgnoreCase(snapshot.getStatus())) {
                return "MCP 工具 " + name + " 未授权或不可用";
            }
            McpServerEntity server = mcpServerDao == null ? null : mcpServerDao.selectById(snapshot.getMcpServerId());
            CapabilityEntity capability = server == null ? null : capabilityDao.selectById(server.getCapabilityId());
            if (capability == null || !"PUBLISHED".equals(capability.getStatus())) {
                return "MCP 服务 " + name + " 未发布或不可用";
            }
        }
        if ("ROLE_MCP".equals(type)) {
            DeviceEntity device = deviceDao.selectById(deviceId);
            String agentId = device == null ? null : StringUtils.trimToNull(device.getAgentId());
            String roleId = StringUtils.trimToNull(text(tool.get("toolRefId")));
            if (agentId == null || roleId == null || !agentId.equals(roleId)) {
                return "角色 MCP 工具不属于当前设备角色";
            }
            RoleMcpToolCatalog catalog = roleMcpCatalogs.computeIfAbsent(roleId, this::loadRoleMcpToolCatalog);
            if (!catalog.available()) return "角色 MCP 接入点不可用";
            if (!catalog.toolNames().contains(name)) {
                return "角色 MCP 工具未在当前角色接入点提供";
            }
        }
        return null;
    }

    private RoleMcpToolCatalog loadRoleMcpToolCatalog(String roleId) {
        if (agentMcpAccessPointService == null) return new RoleMcpToolCatalog(List.of(), false);
        try {
            List<String> names = agentMcpAccessPointService.getAgentMcpToolsListStrict(roleId);
            return new RoleMcpToolCatalog(names == null ? List.of() : List.copyOf(names), true);
        } catch (RuntimeException exception) {
            return new RoleMcpToolCatalog(List.of(), false);
        }
    }

    private Map<String, Object> mcpRuntime(String snapshotId) {
        if (mcpToolDao == null || mcpServerDao == null) return Map.of();
        McpToolSnapshotEntity snapshot = mcpToolDao.selectById(snapshotId);
        if (snapshot == null || !Integer.valueOf(1).equals(snapshot.getApproved())
                || !"ACTIVE".equalsIgnoreCase(snapshot.getStatus())) return Map.of();
        McpServerEntity server = mcpServerDao.selectById(snapshot.getMcpServerId());
        if (server == null) return Map.of();
        CapabilityEntity capability = capabilityDao.selectById(server.getCapabilityId());
        if (capability == null || !"PUBLISHED".equals(capability.getStatus())) return Map.of();
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

    private Map<String, Object> roleMcpRuntime(String agentId) {
        if (StringUtils.isBlank(agentId)) return Map.of();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("executor", "MCP_ENDPOINT");
        runtime.put("agentId", agentId);
        return runtime;
    }

    private void addPluginSecretRefs(String capabilityId, Map<String, Object> defaults) {
        if (pluginDao == null || secretDao == null) return;
        PluginDefinitionEntity plugin = pluginDao.selectByCapabilityId(capabilityId);
        if (plugin == null) return;
        Object configured = parse(plugin.getSecretFieldsJson());
        if (!(configured instanceof Collection<?> fields)) return;
        for (Object raw : fields) {
            String field = StringUtils.trimToNull(String.valueOf(raw));
            if (field == null) continue;
            CapabilitySecretEntity secret = secretDao.selectByCapabilityAndName(
                    capabilityId, field.toLowerCase(Locale.ROOT));
            if (secret != null) defaults.put(field + "_secret_id", secret.getId());
        }
    }

    private Map<String, Object> mergedDefaults(Map<String, Object> content, Map<String, Object> overrides) {
        return mergedDefaults(content, overrides, maps(content.get("tools")));
    }

    private Map<String, Object> mergedDefaults(Map<String, Object> content, Map<String, Object> overrides,
            List<Map<String, Object>> tools) {
        Map<String, Object> result = new LinkedHashMap<>();
        tools.forEach(tool -> result.putAll(map(tool.get("defaultParams"))));
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

    private <T> List<T> rows(Collection<T> value) {
        return value == null ? List.of() : List.copyOf(value);
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

    private record RoleMcpToolCatalog(List<String> toolNames, boolean available) {
    }

    private record ResolvedPublished(CapabilityVersionEntity version, Map<String, Object> content) {
    }
}
