package xiaozhi.modules.companion.capability.service.impl;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilitySecretDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.dao.SkillDefinitionDao;
import xiaozhi.modules.companion.capability.dao.SkillToolMappingDao;
import xiaozhi.modules.companion.capability.dao.SkillTriggerDao;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.McpServerDTO;
import xiaozhi.modules.companion.capability.dto.PluginDefinitionDTO;
import xiaozhi.modules.companion.capability.dto.SkillToolDTO;
import xiaozhi.modules.companion.capability.dto.SkillTriggerDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.SkillDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.SkillToolMappingEntity;
import xiaozhi.modules.companion.capability.entity.SkillTriggerEntity;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.companion.capability.vo.CapabilityVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageImportVO;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;

@Service
@RequiredArgsConstructor
public class CapabilityServiceImpl implements CapabilityService {
    private static final Set<String> WRITABLE_TYPES = Set.of("SKILL", "PLUGIN", "MCP_SERVER");
    private static final Set<String> TOOL_TYPES = Set.of("PLUGIN", "MCP", "ROLE_MCP", "DEVICE_TOOL");
    private static final Set<String> TRIGGER_TYPES = Set.of(
            "KEYWORD", "REGEX", "POSITIVE_EXAMPLE", "NEGATIVE_EXAMPLE");
    private static final Set<String> RESPONSE_MODES = Set.of("LLM", "FIXED");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "DISABLED");
    private static final Set<String> MCP_TRANSPORTS = Set.of("STDIO", "SSE", "STREAMABLE_HTTP");

    private final CapabilityDao capabilityDao;
    private final CapabilityVersionDao versionDao;
    private final SkillDefinitionDao skillDefinitionDao;
    private final SkillTriggerDao triggerDao;
    private final SkillToolMappingDao toolMappingDao;
    private final DeviceSkillMappingDao deviceSkillMappingDao;
    private final PluginDefinitionDao pluginDao;
    private final McpServerDao mcpServerDao;
    private final McpToolSnapshotDao mcpToolDao;
    private final DeviceToolSnapshotDao deviceToolDao;
    private final CapabilitySecretDao secretDao;
    private final CompanionAuditService audit;
    private SkillPackageService skillPackageService;
    private AgentMcpAccessPointService agentMcpAccessPointService;

    @Autowired(required = false)
    public void setAgentMcpAccessPointService(@Lazy AgentMcpAccessPointService service) {
        this.agentMcpAccessPointService = service;
    }

    @Autowired
    public void setSkillPackageService(SkillPackageService skillPackageService) {
        this.skillPackageService = skillPackageService;
    }

    @Override
    public PageData<CapabilityVO> page(String type, String status, String keyword, int page, int limit) {
        QueryWrapper<CapabilityEntity> query = new QueryWrapper<>();
        query.eq("deleted", 0);
        if (StringUtils.isNotBlank(type)) query.eq("type", normalize(type));
        if (StringUtils.isNotBlank(status)) query.eq("status", normalize(status));
        if (StringUtils.isNotBlank(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like("name", value).or().like("description", value));
        }
        query.orderByDesc("updated_at");
        var result = capabilityDao.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(limit, 1), 100)), query);
        return new PageData<>(result.getRecords().stream().map(this::toVO).toList(), result.getTotal());
    }

    @Override
    public CapabilityVO get(String id) {
        CapabilityEntity entity = capabilityDao.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) throw new RenException("能力不存在");
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVO create(Long operatorId, CapabilitySaveDTO dto) {
        validate(dto);
        Date now = new Date();
        CapabilityEntity entity = new CapabilityEntity();
        entity.setId(IdUtil.fastSimpleUUID());
        entity.setCapabilityCode(entity.getId());
        entity.setType(normalize(dto.getType()));
        entity.setName(dto.getName().trim());
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setStatus("DRAFT");
        entity.setDraftVersion(1);
        entity.setCreator(operatorId);
        entity.setUpdater(operatorId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(0);
        if (capabilityDao.insert(entity) != 1) throw new RenException("能力创建失败");
        persistDraft(operatorId, entity.getId(), entity.getDraftVersion(), dto, now);
        audit.record(operatorId, null, "capability.create", "capability", entity.getId(),
                Map.of("type", entity.getType(), "name", entity.getName()));
        CapabilityVO result = toVO(entity, dto);
        fillPackage(result, skillPackageService == null ? null : skillPackageService.selectDraft(entity.getId()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVO update(Long operatorId, String id, CapabilitySaveDTO dto) {
        validate(dto);
        CapabilityEntity entity = requireForUpdate(id);
        if (!entity.getType().equals(normalize(dto.getType()))) throw new RenException("能力类型不能修改");
        Date now = new Date();
        entity.setName(dto.getName().trim());
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setDraftVersion(entity.getDraftVersion() == null ? 1 : entity.getDraftVersion() + 1);
        entity.setUpdater(operatorId);
        entity.setUpdatedAt(now);
        if (capabilityDao.updateById(entity) != 1) throw new RenException("能力不存在");
        persistDraft(operatorId, id, entity.getDraftVersion(), dto, now);
        audit.record(operatorId, null, "capability.update", "capability", id,
                Map.of("type", entity.getType(), "draftVersion", entity.getDraftVersion()));
        CapabilityVO result = toVO(entity, dto);
        fillPackage(result, skillPackageService == null ? null : skillPackageService.selectDraft(id));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVO publish(Long operatorId, String id) {
        CapabilityEntity entity = requireForUpdate(id);
        SkillPackageEntity packageRow = null;
        if ("SKILL".equals(entity.getType())) {
            validatePublishedSkillTools(id);
            if (skillPackageService != null) packageRow = skillPackageService.publishDraft(operatorId, id);
        }
        Map<String, Object> aggregate = packageRow == null ? aggregate(entity) : aggregatePackage(entity, packageRow);
        String content = JsonUtils.toJsonString(canonicalize(aggregate));
        Integer maximum = versionDao.selectMaxVersion(id);
        int version = packageRow == null ? (maximum == null ? 1 : maximum + 1) : packageRow.getVersionNo();
        if (packageRow != null && versionDao.selectVersion(id, version) != null) {
            throw new RenException("Skill 包版本已发布");
        }
        Date now = new Date();

        CapabilityVersionEntity published = new CapabilityVersionEntity();
        published.setId(IdUtil.fastSimpleUUID());
        published.setCapabilityId(id);
        published.setVersionNo(version);
        published.setContentJson(content);
        published.setContentSha256(packageRow == null ? sha256(content) : packageRow.getPackageSha256());
        published.setPublisher(operatorId);
        published.setPublishedAt(now);
        if (versionDao.insert(published) != 1) throw new RenException("能力发布失败");

        entity.setPublishedVersion(version);
        entity.setStatus("PUBLISHED");
        entity.setUpdater(operatorId);
        entity.setUpdatedAt(now);
        if (capabilityDao.updateById(entity) != 1) throw new RenException("能力不存在");
        if ("SKILL".equals(entity.getType())) {
            deviceSkillMappingDao.bumpLatestDeviceConfigVersions(id, now);
        }
        audit.record(operatorId, null, "capability.publish", "capability", id,
                Map.of("type", entity.getType(), "version", version, "sha256", published.getContentSha256()));
        CapabilityVO result = toVO(entity);
        fillPackage(result, packageRow);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVO savePackage(Long operatorId, String id, MultipartFile file) {
        if (skillPackageService == null) throw new RenException("Skill 包服务不可用");
        CapabilityEntity entity = requireForUpdate(id);
        if (!"SKILL".equals(entity.getType())) throw new RenException("只有 Skill 可以上传分发包");
        SkillPackageImportVO inspected = skillPackageService.inspect(file);
        if (inspected.getValidation() == null || !"VALID".equals(inspected.getValidation().getStatus())) {
            throw new RenException("Skill 包尚未通过校验");
        }
        if (!id.equals(inspected.getCapabilityId()) || inspected.getVersion() == null) {
            throw new RenException("Skill 包 id 与目标 Skill 不一致");
        }
        int version = inspected.getVersion();
        if (entity.getPublishedVersion() != null && version <= entity.getPublishedVersion()) {
            throw new RenException("Skill 包版本必须高于已发布版本");
        }
        if (skillPackageService.selectVersion(id, version) != null) {
            throw new RenException("Skill 包版本已存在");
        }
        skillPackageService.saveUploadedDraft(operatorId, id, file);
        SkillPackageEntity packageRow = skillPackageService.selectVersion(id, version);
        if (packageRow == null) throw new RenException("Skill 包草稿保存失败");
        CapabilitySaveDTO dto = packageRequest(packageRow);
        validate(dto);
        Date now = new Date();
        entity.setName(dto.getName().trim());
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setDraftVersion(version);
        entity.setUpdater(operatorId);
        entity.setUpdatedAt(now);
        if (capabilityDao.updateById(entity) != 1) throw new RenException("能力不存在");
        persistSkill(id, dto, now);
        audit.record(operatorId, null, "skill.package.upload", "capability", id,
                Map.of("version", version, "sha256", packageRow.getPackageSha256()));
        CapabilityVO result = toVO(entity, dto);
        fillPackage(result, packageRow);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityVO createPackage(Long operatorId, MultipartFile file) {
        if (skillPackageService == null) throw new RenException("Skill 包服务不可用");
        SkillPackageImportVO inspected = skillPackageService.inspect(file);
        if (inspected.getValidation() == null || !"VALID".equals(inspected.getValidation().getStatus())) {
            throw new RenException("Skill 包尚未通过校验");
        }
        String id = StringUtils.trimToNull(inspected.getCapabilityId());
        Integer version = inspected.getVersion();
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")) {
            throw new RenException("Skill 包 id 无效");
        }
        if (version == null || version < 1) throw new RenException("Skill 包版本无效");
        if (capabilityDao.selectById(id) != null) return savePackage(operatorId, id, file);

        SkillPackageEntity preview = new SkillPackageEntity();
        preview.setCapabilityId(id);
        preview.setVersionNo(version);
        preview.setManifestJson(JsonUtils.toJsonString(inspected.getManifest()));
        preview.setSkillMarkdown(inspected.getSkillMarkdown());
        CapabilitySaveDTO dto = packageRequest(preview);
        validate(dto);

        Date now = new Date();
        CapabilityEntity entity = new CapabilityEntity();
        entity.setId(id);
        entity.setCapabilityCode(id);
        entity.setType("SKILL");
        entity.setName(dto.getName().trim());
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setStatus("DRAFT");
        entity.setDraftVersion(version);
        entity.setCreator(operatorId);
        entity.setUpdater(operatorId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(0);
        if (capabilityDao.insert(entity) != 1) throw new RenException("Skill 创建失败");

        skillPackageService.saveUploadedDraft(operatorId, id, file);
        SkillPackageEntity packageRow = skillPackageService.selectVersion(id, version);
        if (packageRow == null) throw new RenException("Skill 包草稿保存失败");
        persistSkill(id, dto, now);
        audit.record(operatorId, null, "skill.package.create", "capability", id,
                Map.of("version", version, "sha256", packageRow.getPackageSha256()));
        CapabilityVO result = toVO(entity, dto);
        fillPackage(result, packageRow);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long operatorId, String id, String status) {
        String normalized = normalize(status);
        if (!STATUSES.contains(normalized)) throw new RenException("能力状态不支持");
        CapabilityEntity entity = requireForUpdate(id);
        if ("PUBLISHED".equals(normalized) && entity.getPublishedVersion() == null) {
            throw new RenException("能力尚未发布");
        }
        entity.setStatus(normalized);
        entity.setUpdater(operatorId);
        Date now = new Date();
        entity.setUpdatedAt(now);
        if (capabilityDao.updateById(entity) != 1) throw new RenException("能力不存在");
        if ("SKILL".equals(entity.getType())) {
            deviceSkillMappingDao.bumpAllDeviceConfigVersions(id, now);
        } else {
            deviceSkillMappingDao.bumpEveryEnabledDeviceConfigVersion(now);
        }
        audit.record(operatorId, null, "capability.status", "capability", id, Map.of("status", normalized));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long operatorId, String id) {
        CapabilityEntity entity = requireForUpdate(id);
        if ("SKILL".equals(entity.getType()) && deviceSkillMappingDao.countBySkillId(id) > 0) {
            throw new RenException("Skill 正在被设备使用");
        }
        boolean toolReferenced = switch (entity.getType()) {
            case "PLUGIN" -> toolMappingDao.countByToolRef("PLUGIN", id) > 0;
            case "MCP_SERVER" -> mcpToolReferenced(id);
            case "DEVICE_TOOL" -> toolMappingDao.countByToolRef("DEVICE_TOOL", id) > 0;
            default -> false;
        };
        if (toolReferenced) {
            throw new RenException("工具正在被 Skill 使用");
        }
        entity.setDeleted(1);
        entity.setStatus("DELETED");
        entity.setUpdater(operatorId);
        entity.setUpdatedAt(new Date());
        if (capabilityDao.updateById(entity) != 1) throw new RenException("能力不存在");
        audit.record(operatorId, null, "capability.delete", "capability", id, Map.of("type", entity.getType()));
    }

    private boolean mcpToolReferenced(String capabilityId) {
        McpServerEntity server = mcpServerDao.selectByCapabilityId(capabilityId);
        if (server == null) return false;
        return defaultList(mcpToolDao.selectByMcpServerId(server.getId())).stream()
                .anyMatch(tool -> toolMappingDao.countByToolRef("MCP", tool.getId()) > 0);
    }

    private void validate(CapabilitySaveDTO dto) {
        if (dto == null) throw new RenException("能力配置不能为空");
        if (!dto.getUnknownFields().isEmpty()) {
            throw new RenException("能力配置包含不支持的字段: " + String.join(",", dto.getUnknownFields().keySet()));
        }
        String type = normalize(dto.getType());
        if (!WRITABLE_TYPES.contains(type)) throw new RenException("能力类型不支持");
        if (StringUtils.isBlank(dto.getName()) || dto.getName().trim().length() > 100) {
            throw new RenException("能力名称无效");
        }
        if (dto.getDescription() != null && dto.getDescription().trim().length() > 500) {
            throw new RenException("能力说明过长");
        }
        switch (type) {
            case "SKILL" -> validateSkill(dto);
            case "PLUGIN" -> validatePlugin(dto.getPlugin());
            case "MCP_SERVER" -> validateMcp(dto.getMcp());
            default -> throw new RenException("能力类型不支持");
        }
    }

    private void validateSkill(CapabilitySaveDTO dto) {
        if (StringUtils.isBlank(dto.getExecutionPrompt())) throw new RenException("Skill 执行提示词不能为空");
        BigDecimal threshold = dto.getSemanticThreshold();
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) < 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new RenException("Skill 语义阈值无效");
        }
        if (!RESPONSE_MODES.contains(normalize(dto.getResponseMode()))) throw new RenException("Skill 回复策略无效");
        if (dto.getTimeoutMs() == null || dto.getTimeoutMs() < 1000 || dto.getTimeoutMs() > 120000) {
            throw new RenException("Skill 超时时间无效");
        }
        if (dto.getTriggers() == null || dto.getTriggers().isEmpty()) throw new RenException("Skill 触发规则不能为空");
        for (SkillTriggerDTO trigger : dto.getTriggers()) validateTrigger(trigger);
        if (dto.getTools() == null || dto.getTools().isEmpty()) throw new RenException("Skill 工具不能为空");
        for (SkillToolDTO tool : dto.getTools()) validateTool(tool);
    }

    private void validateTrigger(SkillTriggerDTO trigger) {
        if (trigger == null || !TRIGGER_TYPES.contains(normalize(trigger.getType())) || StringUtils.isBlank(trigger.getValue())) {
            throw new RenException("Skill 触发规则无效");
        }
        if ("REGEX".equals(normalize(trigger.getType()))) {
            try {
                Pattern.compile(trigger.getValue());
            } catch (PatternSyntaxException exception) {
                throw new RenException("Skill 正则表达式无效", exception);
            }
        }
    }

    private void validateTool(SkillToolDTO tool) {
        if (tool == null || !TOOL_TYPES.contains(normalize(tool.getToolType()))
                || StringUtils.isAnyBlank(tool.getToolRefId(), tool.getToolName())) {
            throw new RenException("Skill 工具引用无效");
        }
        String type = normalize(tool.getToolType());
        switch (type) {
            case "PLUGIN" -> {
                CapabilityEntity capability = capabilityDao.selectById(tool.getToolRefId());
                PluginDefinitionEntity plugin = pluginDao.selectByCapabilityId(tool.getToolRefId());
                if (capability == null || !"PLUGIN".equals(capability.getType()) || plugin == null
                        || !"PUBLISHED".equals(capability.getStatus())
                        || !tool.getToolName().equals(plugin.getExecutorName())) {
                    throw new RenException("Plugin 工具不存在");
                }
            }
            case "MCP" -> {
                McpToolSnapshotEntity snapshot = mcpToolDao.selectById(tool.getToolRefId());
                if (snapshot == null || !tool.getToolName().equals(snapshot.getToolName())
                        || !Integer.valueOf(1).equals(snapshot.getApproved())
                        || !"ACTIVE".equalsIgnoreCase(snapshot.getStatus())) {
                    throw new RenException("MCP 工具未审批或不可用");
                }
                McpServerEntity server = mcpServerDao.selectById(snapshot.getMcpServerId());
                CapabilityEntity capability = server == null ? null : capabilityDao.selectById(server.getCapabilityId());
                if (capability == null || !"PUBLISHED".equals(capability.getStatus())) {
                    throw new RenException("MCP 服务未发布或不可用");
                }
            }
            case "DEVICE_TOOL" -> {
                DeviceToolSnapshotEntity snapshot = deviceToolDao.selectById(tool.getToolRefId());
                if (snapshot == null || !tool.getToolName().equals(snapshot.getToolName())) {
                    throw new RenException("设备工具不存在");
                }
            }
            case "ROLE_MCP" -> {
                if (!tool.getToolRefId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                        || !tool.getToolName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                    throw new RenException("角色 MCP 工具引用无效");
                }
                if (agentMcpAccessPointService == null) {
                    throw new RenException("角色 MCP 接入点不可用");
                }
                List<String> registered;
                try {
                    registered = agentMcpAccessPointService.getAgentMcpToolsListStrict(tool.getToolRefId());
                } catch (RuntimeException exception) {
                    throw new RenException("角色 MCP 接入点不可用");
                }
                if (registered == null || !registered.contains(tool.getToolName())) {
                    throw new RenException("角色 MCP 工具未登记或不可用");
                }
            }
            default -> throw new RenException("Skill 工具引用无效");
        }
    }

    private void validatePublishedSkillTools(String skillId) {
        List<SkillToolMappingEntity> mappings = defaultList(toolMappingDao.selectBySkillId(skillId));
        if (mappings.isEmpty()) throw new RenException("Skill 工具不能为空");
        for (SkillToolMappingEntity mapping : mappings) {
            SkillToolDTO tool = new SkillToolDTO();
            tool.setToolType(mapping.getToolType());
            tool.setToolRefId(mapping.getToolRefId());
            tool.setToolName(mapping.getToolName());
            validateTool(tool);
        }
    }

    private void validatePlugin(PluginDefinitionDTO plugin) {
        if (plugin == null || StringUtils.isBlank(plugin.getExecutorName()) || plugin.getInputSchema() == null) {
            throw new RenException("Plugin 定义无效");
        }
    }

    private void validateMcp(McpServerDTO mcp) {
        if (mcp == null || !MCP_TRANSPORTS.contains(normalize(mcp.getTransport()))
                || mcp.getConnectionConfig() == null) {
            throw new RenException("MCP 服务定义无效");
        }
        if (containsInlineSecret(mcp.getConnectionConfig())) {
            throw new RenException("MCP 敏感配置必须使用密钥引用");
        }
        if ("STDIO".equals(normalize(mcp.getTransport()))) {
            Map<String, Object> approved = ApprovedMcpCommandTemplates.resolve(mcp.getConnectionConfig());
            if (approved == null || !approved.equals(mcp.getApprovedCommandTemplate())) {
                throw new RenException("MCP stdio 命令不在服务端批准模板中");
            }
        } else {
            String url = mcp.getConnectionConfig().get("url") instanceof String value
                    ? StringUtils.trimToNull(value) : null;
            if (url == null || !url.matches("^https?://[^\\s]+$")
                    || containsUrlCredentials(url)
                    || mcp.getConnectionConfig().containsKey("command")
                    || mcp.getConnectionConfig().containsKey("args")
                    || mcp.getApprovedCommandTemplate() != null) {
                throw new RenException("MCP 网络连接配置无效");
            }
        }
    }

    private boolean containsUrlCredentials(String url) {
        try {
            URI parsed = URI.create(url);
            if (parsed.getRawUserInfo() != null) return true;
            String query = parsed.getRawQuery();
            if (query == null) return false;
            for (String pair : query.split("&")) {
                String key = pair.split("=", 2)[0].toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                if (key.contains("authorization") || key.contains("password")
                        || key.contains("secret") || key.contains("credential")
                        || key.endsWith("token") || key.endsWith("apikey")
                        || key.endsWith("privatekey") || key.endsWith("accesskey")) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private boolean containsInlineSecret(Object value) {
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                Object item = entry.getValue();
                boolean sensitive = key.contains("authorization") || key.contains("password")
                        || key.contains("secret") || key.contains("credential")
                        || key.contains("cookie") || key.endsWith("token")
                        || key.endsWith("apikey") || key.endsWith("privatekey")
                        || key.endsWith("accesskey");
                if (sensitive && item != null && (!(item instanceof String text) || StringUtils.isNotBlank(text))) {
                    return true;
                }
                if (containsInlineSecret(item)) return true;
            }
        } else if (value instanceof Collection<?> source) {
            for (Object item : source) if (containsInlineSecret(item)) return true;
        }
        return false;
    }

    private void persistDraft(Long operatorId, String capabilityId, int version, CapabilitySaveDTO dto, Date now) {
        switch (normalize(dto.getType())) {
            case "SKILL" -> {
                if (skillPackageService != null) {
                    skillPackageService.saveOnlineDraft(operatorId, capabilityId, version, dto);
                }
                persistSkill(capabilityId, dto, now);
            }
            case "PLUGIN" -> persistPlugin(capabilityId, dto.getPlugin(), now);
            case "MCP_SERVER" -> persistMcp(capabilityId, dto.getMcp(), now);
            default -> throw new RenException("能力类型不支持");
        }
    }

    private void persistSkill(String capabilityId, CapabilitySaveDTO dto, Date now) {
        SkillDefinitionEntity definition = skillDefinitionDao.selectByCapabilityId(capabilityId);
        boolean existing = definition != null;
        if (!existing) {
            definition = new SkillDefinitionEntity();
            definition.setId(IdUtil.fastSimpleUUID());
            definition.setCapabilityId(capabilityId);
            definition.setCreatedAt(now);
        }
        definition.setExecutionPrompt(dto.getExecutionPrompt().trim());
        definition.setTriggerMode("MIXED");
        definition.setRuleMode("ANY");
        definition.setSemanticThreshold(dto.getSemanticThreshold());
        definition.setResponseMode(normalize(dto.getResponseMode()));
        definition.setTimeoutMs(dto.getTimeoutMs());
        definition.setFailureMessage(StringUtils.trimToNull(dto.getFailureMessage()));
        definition.setUpdatedAt(now);
        int written = existing ? skillDefinitionDao.updateById(definition) : skillDefinitionDao.insert(definition);
        if (written != 1) throw new RenException("Skill 草稿保存失败");

        triggerDao.deleteBySkillId(capabilityId);
        for (SkillTriggerDTO item : dto.getTriggers()) {
            SkillTriggerEntity trigger = new SkillTriggerEntity();
            trigger.setId(IdUtil.getSnowflakeNextId());
            trigger.setSkillId(capabilityId);
            trigger.setTriggerType(normalize(item.getType()));
            trigger.setPatternText(item.getValue().trim());
            trigger.setPriority(item.getPriority() == null ? 0 : item.getPriority());
            trigger.setCaseSensitive(Boolean.TRUE.equals(item.getCaseSensitive()) ? 1 : 0);
            trigger.setEnabled(Boolean.FALSE.equals(item.getEnabled()) ? 0 : 1);
            trigger.setCreatedAt(now);
            trigger.setUpdatedAt(now);
            if (triggerDao.insert(trigger) != 1) throw new RenException("Skill 触发规则保存失败");
        }

        toolMappingDao.deleteBySkillId(capabilityId);
        for (SkillToolDTO item : dto.getTools()) {
            SkillToolMappingEntity mapping = new SkillToolMappingEntity();
            mapping.setId(IdUtil.getSnowflakeNextId());
            mapping.setSkillId(capabilityId);
            mapping.setToolType(normalize(item.getToolType()));
            mapping.setToolRefId(item.getToolRefId().trim());
            mapping.setToolName(item.getToolName().trim());
            mapping.setToolAlias(StringUtils.trimToNull(item.getAlias()));
            mapping.setPurpose(StringUtils.trimToNull(item.getPurpose()));
            mapping.setDefaultParamsJson(item.getDefaultParams() == null ? null
                    : JsonUtils.toJsonString(canonicalize(item.getDefaultParams())));
            mapping.setRequired(Boolean.TRUE.equals(item.getRequired()) ? 1 : 0);
            mapping.setSortOrder(item.getSortOrder() == null ? 0 : item.getSortOrder());
            mapping.setCreatedAt(now);
            mapping.setUpdatedAt(now);
            if (toolMappingDao.insert(mapping) != 1) throw new RenException("Skill 工具保存失败");
        }
    }

    private void persistPlugin(String capabilityId, PluginDefinitionDTO dto, Date now) {
        PluginDefinitionEntity entity = pluginDao.selectByCapabilityId(capabilityId);
        boolean existing = entity != null;
        if (!existing) {
            entity = new PluginDefinitionEntity();
            entity.setId(IdUtil.fastSimpleUUID());
            entity.setCapabilityId(capabilityId);
            entity.setCreatedAt(now);
        }
        entity.setExecutorName(dto.getExecutorName().trim());
        entity.setInputSchemaJson(json(dto.getInputSchema()));
        entity.setConfigSchemaJson(json(dto.getConfigSchema()));
        entity.setSecretFieldsJson(json(dto.getSecretFields()));
        entity.setDefaultConfigJson(json(dto.getDefaultConfig()));
        entity.setUpdatedAt(now);
        int written = existing ? pluginDao.updateById(entity) : pluginDao.insert(entity);
        if (written != 1) throw new RenException("Plugin 草稿保存失败");
    }

    private void persistMcp(String capabilityId, McpServerDTO dto, Date now) {
        McpServerEntity entity = mcpServerDao.selectByCapabilityId(capabilityId);
        boolean existing = entity != null;
        if (!existing) {
            entity = new McpServerEntity();
            entity.setId(IdUtil.fastSimpleUUID());
            entity.setCapabilityId(capabilityId);
            entity.setHealthStatus("UNKNOWN");
            entity.setCreatedAt(now);
        }
        entity.setTransport(normalize(dto.getTransport()));
        entity.setConnectionConfigJson(json(dto.getConnectionConfig()));
        entity.setSecretRefsJson(json(dto.getSecretRefs()));
        entity.setApprovedCommandTemplateJson(json(dto.getApprovedCommandTemplate()));
        entity.setUpdatedAt(now);
        int written = existing ? mcpServerDao.updateById(entity) : mcpServerDao.insert(entity);
        if (written != 1) throw new RenException("MCP 服务草稿保存失败");
    }

    private CapabilityEntity requireForUpdate(String id) {
        CapabilityEntity entity = capabilityDao.selectForUpdate(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) throw new RenException("能力不存在");
        return entity;
    }

    private CapabilityVO toVO(CapabilityEntity entity) {
        CapabilityVO vo = commonVO(entity);
        switch (entity.getType()) {
            case "SKILL" -> {
                fillSkill(vo, entity.getId());
                fillPackage(vo, currentPackage(entity));
            }
            case "PLUGIN" -> vo.setPlugin(toDTO(pluginDao.selectByCapabilityId(entity.getId())));
            case "MCP_SERVER" -> vo.setMcp(toDTO(mcpServerDao.selectByCapabilityId(entity.getId())));
            default -> {
            }
        }
        return vo;
    }

    private CapabilityVO toVO(CapabilityEntity entity, CapabilitySaveDTO dto) {
        CapabilityVO vo = commonVO(entity);
        if ("SKILL".equals(entity.getType())) {
            vo.setExecutionPrompt(dto.getExecutionPrompt());
            vo.setSemanticThreshold(dto.getSemanticThreshold());
            vo.setResponseMode(normalize(dto.getResponseMode()));
            vo.setTimeoutMs(dto.getTimeoutMs());
            vo.setFailureMessage(dto.getFailureMessage());
            vo.setDeviceRequirements(dto.getDeviceRequirements());
            vo.setTriggers(dto.getTriggers() == null ? List.of() : List.copyOf(dto.getTriggers()));
            vo.setTools(dto.getTools() == null ? List.of() : List.copyOf(dto.getTools()));
            fillPackage(vo, currentPackage(entity));
        } else if ("PLUGIN".equals(entity.getType())) {
            vo.setPlugin(dto.getPlugin());
        } else if ("MCP_SERVER".equals(entity.getType())) {
            vo.setMcp(dto.getMcp());
        }
        return vo;
    }

    private SkillPackageEntity currentPackage(CapabilityEntity entity) {
        if (skillPackageService == null) return null;
        SkillPackageEntity draft = skillPackageService.selectDraft(entity.getId());
        if (draft != null) return draft;
        return entity.getPublishedVersion() == null ? null
                : skillPackageService.selectVersion(entity.getId(), entity.getPublishedVersion());
    }

    private CapabilityVO commonVO(CapabilityEntity entity) {
        CapabilityVO vo = new CapabilityVO();
        vo.setId(entity.getId());
        vo.setType(entity.getType());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setStatus(entity.getStatus());
        vo.setDraftVersion(entity.getDraftVersion());
        vo.setPublishedVersion(entity.getPublishedVersion());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private void fillSkill(CapabilityVO vo, String skillId) {
        SkillDefinitionEntity definition = skillDefinitionDao.selectByCapabilityId(skillId);
        if (definition == null) return;
        vo.setExecutionPrompt(definition.getExecutionPrompt());
        vo.setSemanticThreshold(definition.getSemanticThreshold());
        vo.setResponseMode(definition.getResponseMode());
        vo.setTimeoutMs(definition.getTimeoutMs());
        vo.setFailureMessage(definition.getFailureMessage());
        List<SkillTriggerEntity> triggers = defaultList(triggerDao.selectBySkillId(skillId));
        vo.setTriggers(triggers.stream().map(this::toDTO).toList());
        List<SkillToolMappingEntity> tools = defaultList(toolMappingDao.selectBySkillId(skillId));
        vo.setTools(tools.stream().map(this::toDTO).toList());
    }

    private Map<String, Object> aggregate(CapabilityEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("type", entity.getType());
        result.put("name", entity.getName());
        result.put("description", entity.getDescription());
        if ("SKILL".equals(entity.getType())) {
            SkillDefinitionEntity definition = skillDefinitionDao.selectByCapabilityId(entity.getId());
            if (definition == null) throw new RenException("Skill 草稿不存在");
            result.put("executionPrompt", definition.getExecutionPrompt());
            result.put("triggerMode", definition.getTriggerMode());
            result.put("ruleMode", definition.getRuleMode());
            result.put("semanticThreshold", definition.getSemanticThreshold());
            result.put("responseMode", definition.getResponseMode());
            result.put("timeoutMs", definition.getTimeoutMs());
            result.put("failureMessage", definition.getFailureMessage());
            result.put("overridableFields", parseJson(definition.getOverridableFieldsJson()));
            result.put("triggers", defaultList(triggerDao.selectBySkillId(entity.getId())).stream()
                    .map(this::triggerMap).toList());
            result.put("tools", defaultList(toolMappingDao.selectBySkillId(entity.getId())).stream()
                    .map(this::toolMap).toList());
        } else if ("PLUGIN".equals(entity.getType())) {
            PluginDefinitionEntity definition = pluginDao.selectByCapabilityId(entity.getId());
            if (definition == null) throw new RenException("Plugin 草稿不存在");
            result.put("plugin", pluginMap(definition));
        } else if ("MCP_SERVER".equals(entity.getType())) {
            McpServerEntity definition = mcpServerDao.selectByCapabilityId(entity.getId());
            if (definition == null) throw new RenException("MCP 服务草稿不存在");
            result.put("mcp", mcpMap(definition));
        }
        return result;
    }

    private Map<String, Object> aggregatePackage(CapabilityEntity entity, SkillPackageEntity packageRow) {
        Map<String, Object> manifest = JsonUtils.parseMap(packageRow.getManifestJson());
        Map<String, Object> runtime = asMap(manifest.get("runtime"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("type", "SKILL");
        result.put("name", manifest.get("name"));
        result.put("description", manifest.get("description"));
        result.put("executionPrompt", packageRow.getSkillMarkdown());
        result.put("triggerMode", "MIXED");
        result.put("ruleMode", "ANY");
        result.put("semanticThreshold", runtime.get("semanticThreshold"));
        result.put("responseMode", runtime.get("responseMode"));
        result.put("timeoutMs", runtime.get("timeoutMs"));
        result.put("failureMessage", runtime.get("failureMessage"));
        result.put("overridableFields", List.of());
        result.put("triggers", manifest.getOrDefault("triggers", List.of()));
        List<Map<String, Object>> tools = new ArrayList<>();
        Object rawTools = manifest.get("tools");
        if (rawTools instanceof Collection<?> values) {
            int order = 0;
            for (Object value : values) {
                Map<String, Object> tool = asMap(value);
                Map<String, Object> projected = new LinkedHashMap<>();
                projected.put("toolType", tool.get("type"));
                projected.put("toolRefId", tool.get("ref"));
                projected.put("toolName", tool.get("name"));
                projected.put("alias", tool.get("alias"));
                projected.put("purpose", tool.get("purpose"));
                projected.put("defaultParams", tool.getOrDefault("defaults", Map.of()));
                projected.put("required", !tool.containsKey("required") || Boolean.TRUE.equals(tool.get("required")));
                projected.put("sortOrder", order++);
                tools.add(projected);
            }
        }
        result.put("tools", tools);
        result.put("deviceRequirements", manifest.getOrDefault("deviceRequirements", List.of()));
        result.put("packageVersion", packageRow.getVersionNo());
        result.put("packageSha256", packageRow.getPackageSha256());
        return result;
    }

    private CapabilitySaveDTO packageRequest(SkillPackageEntity packageRow) {
        Map<String, Object> manifest = JsonUtils.parseMap(packageRow.getManifestJson());
        Map<String, Object> runtime = asMap(manifest.get("runtime"));
        if (runtime == null) throw new RenException("Skill 包 runtime 配置缺失");
        CapabilitySaveDTO dto = new CapabilitySaveDTO();
        dto.setType("SKILL");
        dto.setName(String.valueOf(manifest.get("name")));
        Object description = manifest.get("description");
        dto.setDescription(description == null ? null : String.valueOf(description));
        dto.setExecutionPrompt(packageRow.getSkillMarkdown());
        dto.setSemanticThreshold(decimal(runtime.get("semanticThreshold"), BigDecimal.valueOf(0.7)));
        dto.setResponseMode(String.valueOf(runtime.getOrDefault("responseMode", "LLM")));
        dto.setTimeoutMs(integer(runtime.get("timeoutMs"), 30000));
        Object failure = runtime.get("failureMessage");
        dto.setFailureMessage(failure == null ? null : String.valueOf(failure));
        dto.setDeviceRequirements(manifest.get("deviceRequirements"));
        List<SkillTriggerDTO> triggers = new ArrayList<>();
        if (manifest.get("triggers") instanceof Collection<?> values) {
            for (Object value : values) {
                Map<String, Object> source = asMap(value);
                if (source == null) throw new RenException("Skill 包触发规则无效");
                SkillTriggerDTO trigger = new SkillTriggerDTO();
                trigger.setType(String.valueOf(source.get("type")));
                trigger.setValue(String.valueOf(source.get("value")));
                trigger.setPriority(integer(source.get("priority"), 0));
                trigger.setCaseSensitive(Boolean.TRUE.equals(source.get("caseSensitive")));
                trigger.setEnabled(!Boolean.FALSE.equals(source.get("enabled")));
                triggers.add(trigger);
            }
        }
        dto.setTriggers(triggers);
        List<SkillToolDTO> tools = new ArrayList<>();
        if (manifest.get("tools") instanceof Collection<?> values) {
            int order = 0;
            for (Object value : values) {
                Map<String, Object> source = asMap(value);
                if (source == null) throw new RenException("Skill 包工具引用无效");
                SkillToolDTO tool = new SkillToolDTO();
                tool.setToolType(String.valueOf(source.get("type")));
                tool.setToolRefId(String.valueOf(source.get("ref")));
                tool.setToolName(String.valueOf(source.get("name")));
                if (source.get("alias") != null) tool.setAlias(String.valueOf(source.get("alias")));
                if (source.get("purpose") != null) tool.setPurpose(String.valueOf(source.get("purpose")));
                tool.setDefaultParams(asMap(source.get("defaults")));
                tool.setRequired(!source.containsKey("required") || Boolean.TRUE.equals(source.get("required")));
                tool.setSortOrder(order++);
                tools.add(tool);
            }
        }
        dto.setTools(tools);
        return dto;
    }

    private Integer integer(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new RenException("Skill 包整数配置无效", exception);
        }
    }

    private BigDecimal decimal(Object value, BigDecimal defaultValue) {
        if (value == null) return defaultValue;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new RenException("Skill 包数值配置无效", exception);
        }
    }

    private void fillPackage(CapabilityVO vo, SkillPackageEntity row) {
        if (row == null) return;
        Map<String, Object> manifest = JsonUtils.parseMap(row.getManifestJson());
        if (manifest != null) vo.setDeviceRequirements(manifest.get("deviceRequirements"));
        vo.setPackageVersion(row.getVersionNo());
        vo.setPackageSha256(row.getPackageSha256());
        vo.setPackageSource(row.getSourceType());
        vo.setPackageValidationStatus(row.getValidationStatus());
    }

    private Map<String, Object> triggerMap(SkillTriggerEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", entity.getTriggerType());
        result.put("value", entity.getPatternText());
        result.put("priority", entity.getPriority());
        result.put("caseSensitive", Integer.valueOf(1).equals(entity.getCaseSensitive()));
        result.put("enabled", !Integer.valueOf(0).equals(entity.getEnabled()));
        return result;
    }

    private Map<String, Object> toolMap(SkillToolMappingEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolType", entity.getToolType());
        result.put("toolRefId", entity.getToolRefId());
        result.put("toolName", entity.getToolName());
        result.put("alias", entity.getToolAlias());
        result.put("purpose", entity.getPurpose());
        result.put("defaultParams", parseJson(entity.getDefaultParamsJson()));
        result.put("required", Integer.valueOf(1).equals(entity.getRequired()));
        result.put("sortOrder", entity.getSortOrder());
        return result;
    }

    private Map<String, Object> pluginMap(PluginDefinitionEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executorName", entity.getExecutorName());
        result.put("inputSchema", parseJson(entity.getInputSchemaJson()));
        result.put("configSchema", parseJson(entity.getConfigSchemaJson()));
        result.put("secretFields", parseJson(entity.getSecretFieldsJson()));
        result.put("defaultConfig", parseJson(entity.getDefaultConfigJson()));
        return result;
    }

    private Map<String, Object> mcpMap(McpServerEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", entity.getTransport());
        result.put("connectionConfig", parseJson(entity.getConnectionConfigJson()));
        result.put("secretRefs", parseJson(entity.getSecretRefsJson()));
        result.put("approvedCommandTemplate", parseJson(entity.getApprovedCommandTemplateJson()));
        result.put("healthStatus", entity.getHealthStatus());
        result.put("lastError", entity.getLastError());
        result.put("lastCheckedAt", entity.getLastCheckedAt());
        return result;
    }

    private SkillTriggerDTO toDTO(SkillTriggerEntity entity) {
        SkillTriggerDTO dto = new SkillTriggerDTO();
        dto.setType(entity.getTriggerType());
        dto.setValue(entity.getPatternText());
        dto.setPriority(entity.getPriority());
        dto.setCaseSensitive(Integer.valueOf(1).equals(entity.getCaseSensitive()));
        dto.setEnabled(!Integer.valueOf(0).equals(entity.getEnabled()));
        return dto;
    }

    private SkillToolDTO toDTO(SkillToolMappingEntity entity) {
        SkillToolDTO dto = new SkillToolDTO();
        dto.setToolType(entity.getToolType());
        dto.setToolRefId(entity.getToolRefId());
        dto.setToolName(entity.getToolName());
        dto.setAlias(entity.getToolAlias());
        dto.setPurpose(entity.getPurpose());
        Object defaults = parseJson(entity.getDefaultParamsJson());
        if (defaults instanceof Map<?, ?> map) dto.setDefaultParams(stringMap(map));
        dto.setRequired(Integer.valueOf(1).equals(entity.getRequired()));
        dto.setSortOrder(entity.getSortOrder());
        return dto;
    }

    private PluginDefinitionDTO toDTO(PluginDefinitionEntity entity) {
        if (entity == null) return null;
        PluginDefinitionDTO dto = new PluginDefinitionDTO();
        dto.setExecutorName(entity.getExecutorName());
        dto.setInputSchema(asMap(parseJson(entity.getInputSchemaJson())));
        dto.setConfigSchema(asMap(parseJson(entity.getConfigSchemaJson())));
        Object fields = parseJson(entity.getSecretFieldsJson());
        if (fields instanceof Collection<?> values) dto.setSecretFields(values.stream().map(String::valueOf).toList());
        dto.setDefaultConfig(asMap(parseJson(entity.getDefaultConfigJson())));
        return dto;
    }

    private McpServerDTO toDTO(McpServerEntity entity) {
        if (entity == null) return null;
        McpServerDTO dto = new McpServerDTO();
        dto.setTransport(entity.getTransport());
        dto.setConnectionConfig(asMap(parseJson(entity.getConnectionConfigJson())));
        Map<String, Object> refs = asMap(parseJson(entity.getSecretRefsJson()));
        if (refs != null) {
            Map<String, String> values = new LinkedHashMap<>();
            refs.forEach((key, value) -> values.put(key, value == null ? null : String.valueOf(value)));
            dto.setSecretRefs(values);
        }
        dto.setApprovedCommandTemplate(asMap(parseJson(entity.getApprovedCommandTemplateJson())));
        dto.setHealthStatus(entity.getHealthStatus());
        dto.setLastError(entity.getLastError());
        dto.setLastCheckedAt(entity.getLastCheckedAt());
        return dto;
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof Collection<?> values) {
            List<Object> result = new ArrayList<>(values.size());
            values.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private String json(Object value) {
        return value == null ? null : JsonUtils.toJsonString(canonicalize(value));
    }

    private Object parseJson(String value) {
        return StringUtils.isBlank(value) ? null : JsonUtils.parseObject(value, Object.class);
    }

    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? stringMap(map) : null;
    }

    private Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalize(String value) {
        return StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
