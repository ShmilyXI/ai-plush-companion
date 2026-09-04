package zixuan.modules.companion.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.SensitiveDataUtils;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.agent.service.AgentSnapshotService;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.companion.dto.CompanionProfileSaveDTO;
import zixuan.modules.companion.dto.CompanionSkillBindingSaveDTO;
import zixuan.modules.companion.service.CompanionProfileService;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.companion.vo.CompanionProfileVO;
import zixuan.modules.companion.model.dao.CompanionProfileModelDao;
import zixuan.modules.companion.model.dto.CompanionProfileModelSaveDTO;
import zixuan.modules.companion.model.entity.CompanionProfileModelEntity;
import zixuan.modules.companion.model.service.CompanionEffectiveModelService;
import zixuan.modules.companion.model.service.CompanionPrivateModelService;
import zixuan.modules.companion.model.vo.CompanionModelOptionVO;
import zixuan.modules.companion.model.vo.CompanionProfileModelVO;
import zixuan.modules.model.entity.ModelConfigEntity;
import cn.hutool.json.JSONObject;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.timbre.entity.TimbreEntity;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.voiceclone.entity.VoiceCloneEntity;
import zixuan.modules.voiceclone.service.VoiceCloneService;
import org.springframework.beans.factory.annotation.Autowired;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.companion.vo.CompanionBoundDeviceVO;
import zixuan.modules.companion.vo.CompanionSkillBindingVO;
import zixuan.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import zixuan.modules.companion.capability.entity.AgentVersionSkillBindingEntity;

@Service
public class CompanionProfileServiceImpl implements CompanionProfileService {
    private static final String DEFAULT_COMPANION_TEMPLATE_ID = "template-zixuan";
    private static final List<String> EDITABLE_MODEL_TYPES = List.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private final AgentDao agentDao;
    private final AgentService agentService;
    private final AgentTemplateService templateService;
    private final AgentSnapshotService snapshotService;
    private final ModelConfigService modelConfigService;
    private final TimbreService timbreService;
    private final VoiceCloneService voiceCloneService;
    private final CompanionSubscriptionService subscriptionService;
    private final SysUserDao sysUserDao;
    private final CompanionProfileModelDao profileModelDao;
    private final CompanionEffectiveModelService effectiveModels;
    private final CompanionPrivateModelService privateModels;
    @Autowired(required = false)
    private DeviceService deviceService;
    @Autowired(required = false)
    private AgentVersionSkillBindingDao skillBindingDao;

    public CompanionProfileServiceImpl(AgentDao agentDao, AgentService agentService,
            AgentTemplateService templateService, AgentSnapshotService snapshotService,
            ModelConfigService modelConfigService, TimbreService timbreService,
            VoiceCloneService voiceCloneService, CompanionSubscriptionService subscriptionService,
            SysUserDao sysUserDao, CompanionProfileModelDao profileModelDao,
            CompanionEffectiveModelService effectiveModels, CompanionPrivateModelService privateModels) {
        this(agentDao, agentService, templateService, snapshotService, modelConfigService, timbreService,
                voiceCloneService, subscriptionService, sysUserDao, profileModelDao, effectiveModels,
                privateModels, null);
    }

    @Autowired
    public CompanionProfileServiceImpl(AgentDao agentDao, AgentService agentService,
            AgentTemplateService templateService, AgentSnapshotService snapshotService,
            ModelConfigService modelConfigService, TimbreService timbreService,
            VoiceCloneService voiceCloneService, CompanionSubscriptionService subscriptionService,
            SysUserDao sysUserDao, CompanionProfileModelDao profileModelDao,
            CompanionEffectiveModelService effectiveModels, CompanionPrivateModelService privateModels,
            DeviceService deviceService) {
        this.agentDao = agentDao;
        this.agentService = agentService;
        this.templateService = templateService;
        this.snapshotService = snapshotService;
        this.modelConfigService = modelConfigService;
        this.timbreService = timbreService;
        this.voiceCloneService = voiceCloneService;
        this.subscriptionService = subscriptionService;
        this.sysUserDao = sysUserDao;
        this.profileModelDao = profileModelDao;
        this.effectiveModels = effectiveModels;
        this.privateModels = privateModels;
        this.deviceService = deviceService;
    }

    @Override
    public List<CompanionProfileVO> list(Long userId) {
        List<AgentEntity> profiles = agentDao.selectList(new QueryWrapper<AgentEntity>()
                .eq("user_id", userId)
                .eq("companion_enabled", 1)
                .orderByDesc("created_at"));
        Set<String> modelIds = profiles.stream()
                .flatMap(profile -> java.util.stream.Stream.of(profile.getLlmModelId(), profile.getTtsModelId()))
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Set<String> voiceIds = profiles.stream()
                .map(AgentEntity::getTtsVoiceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> modelNames = modelConfigService.getModelNamesByIds(modelIds);
        Map<String, String> voiceNames = timbreService.getTimbreNamesByIds(voiceIds);
        return profiles
                .stream()
                .map(profile -> toVO(profile, modelNames, voiceNames))
                .toList();
    }

    @Override
    public CompanionProfileVO get(Long userId, String id) {
        return toVO(requireOwned(userId, id));
    }

    @Override
    public List<CompanionModelOptionVO> modelOptions(Long userId, String id) {
        requireOwned(userId, id);
        return effectiveModels.options(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createFromTemplate(Long userId, String templateId, String name) {
        if (sysUserDao.selectByIdForUpdate(userId) == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return createFromTemplateWithLockedUser(userId, templateId, name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resolveForDeviceBinding(Long userId, String requestedProfileId, String templateId,
            String defaultName) {
        if (sysUserDao.selectByIdForUpdate(userId) == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        if (requestedProfileId != null && !requestedProfileId.isBlank()) {
            return requireOwned(userId, requestedProfileId).getId();
        }
        List<AgentEntity> existing = agentDao.selectList(new QueryWrapper<AgentEntity>()
                .eq("user_id", userId)
                .eq("companion_enabled", 1)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        return createFromTemplateWithLockedUser(userId, templateId, defaultName);
    }

    private String createFromTemplateWithLockedUser(Long userId, String templateId, String name) {
        AgentTemplateEntity template = templateService.getById(templateId);
        if (template == null && DEFAULT_COMPANION_TEMPLATE_ID.equals(templateId)) {
            template = templateService.getDefaultTemplate();
        }
        if (template == null) {
            throw new RenException(ErrorCode.AGENT_TEMPLATE_NOT_FOUND);
        }
        long currentCount = agentDao.selectCount(new QueryWrapper<AgentEntity>()
                .eq("user_id", userId)
                .eq("companion_enabled", 1));
        subscriptionService.requireProfileSlot(userId, currentCount);

        AgentEntity entity = fromTemplate(template, userId, name);
        if (!agentService.insert(entity)) {
            throw new RenException(ErrorCode.ADD_DATA_FAILED);
        }
        snapshotService.createSnapshot(entity.getId(), "initial");
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, String id, CompanionProfileSaveDTO dto) {
        AgentEntity current = requireOwned(agentDao.selectByIdForUpdate(id), userId);
        AgentEntity updated = copy(current);
        apply(dto, updated);
        List<CompanionProfileModelEntity> existingBindings = List.of();
        boolean voiceFieldSubmitted = dto.getTtsVoiceId() != null;
        boolean voiceSubmitted = voiceFieldSubmitted && !dto.getTtsVoiceId().isEmpty();
        boolean voiceCleared = voiceFieldSubmitted && dto.getTtsVoiceId().isEmpty();
        boolean ttsBindingChanged = false;
        if (dto.getModels() != null) {
            existingBindings = profileModelDao.selectByAgentIdForUpdate(id);
            if (existingBindings == null) existingBindings = List.of();
            ttsBindingChanged = ttsBindingChanged(dto.getModels(), current, existingBindings);
            validateModelBindings(dto.getModels(), updated,
                    existingBindings);
        } else if (voiceFieldSubmitted) {
            existingBindings = profileModelDao.selectByAgentIdForUpdate(id);
            if (existingBindings == null) existingBindings = List.of();
            rematerializeDefaultTtsForVoiceUpdate(current, updated, existingBindings);
        }
        validateSkillBindingInputs(dto.getSkills());
        boolean ttsModelChanged = !Objects.equals(current.getTtsModelId(), updated.getTtsModelId());
        if (voiceCleared) {
            validateClearedTtsVoice(updated.getTtsModelId());
        } else if (voiceSubmitted) {
            validateTtsVoice(userId, updated.getTtsVoiceId(), updated.getTtsModelId(), updated, voiceSubmitted);
        } else if (ttsBindingChanged || ttsModelChanged) {
            if (updated.getTtsVoiceId() == null || updated.getTtsVoiceId().isBlank()) {
                validateClearedTtsVoice(updated.getTtsModelId());
            } else {
                validateTtsVoice(userId, updated.getTtsVoiceId(), updated.getTtsModelId(), updated, false);
            }
        }
        updated.setUpdater(userId);
        updated.setUpdatedAt(new Date());

        snapshotService.createSnapshot(id, "current");
        if (!agentService.updateById(updated)) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        if (dto.getModels() != null) replaceModelBindings(id, dto.getModels(), existingBindings);
        snapshotService.createSnapshot(id, "companion-update");
        if (dto.getSkills() != null) {
            replaceSkillBindings(id, snapshotService.getCurrentVersionNo(id), dto.getSkills());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restorePrompt(Long userId, String id) {
        AgentEntity updated = copy(requireOwned(agentDao.selectByIdForUpdate(id), userId));
        if (updated.getCompanionTemplateId() == null || updated.getCompanionTemplateId().isBlank()) {
            throw new RenException(ErrorCode.AGENT_TEMPLATE_NOT_FOUND);
        }
        AgentTemplateEntity template = templateService.getById(updated.getCompanionTemplateId());
        if (template == null) {
            throw new RenException(ErrorCode.AGENT_TEMPLATE_NOT_FOUND);
        }

        updated.setSystemPrompt(template.getSystemPrompt());
        updated.setUpdater(userId);
        updated.setUpdatedAt(new Date());
        snapshotService.createSnapshot(id, "current");
        if (!agentService.updateById(updated)) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        snapshotService.createSnapshot(id, "companion-restore-prompt");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String id) {
        // Lock order shared with device/profile binding: ai_agent first, then ai_device references.
        requireOwned(agentDao.selectByIdForUpdate(id), userId);
        Integer deviceCount = agentDao.getDeviceCountByAgentId(id);
        if (deviceCount != null && deviceCount > 0) {
            throw new RenException(ErrorCode.DELETE_DATA_FAILED);
        }
        agentService.deleteAgent(id);
    }

    private AgentEntity requireOwned(Long userId, String id) {
        return requireOwned(agentService.selectById(id), userId);
    }

    private AgentEntity requireOwned(AgentEntity entity, Long userId) {
        if (entity == null || !Integer.valueOf(1).equals(entity.getCompanionEnabled())) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (userId == null || !userId.equals(entity.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return entity;
    }

    private AgentEntity fromTemplate(AgentTemplateEntity template, Long userId, String name) {
        AgentEntity entity = new AgentEntity();
        entity.setUserId(userId);
        entity.setAgentName(name);
        entity.setAsrModelId(template.getAsrModelId());
        entity.setVadModelId(template.getVadModelId());
        entity.setLlmModelId(template.getLlmModelId());
        entity.setVllmModelId(template.getVllmModelId());
        entity.setTtsModelId(template.getTtsModelId());
        entity.setTtsVoiceId(template.getTtsVoiceId());
        entity.setTtsLanguage(template.getTtsLanguage());
        entity.setTtsVolume(template.getTtsVolume());
        entity.setTtsRate(template.getTtsRate());
        entity.setTtsPitch(template.getTtsPitch());
        entity.setMemModelId(template.getMemModelId());
        entity.setIntentModelId(template.getIntentModelId());
        entity.setChatHistoryConf(template.getChatHistoryConf());
        entity.setSystemPrompt(template.getSystemPrompt());
        entity.setCompanionCueConfig(template.getCompanionCueConfig());
        entity.setSummaryMemory(template.getSummaryMemory());
        entity.setLangCode(template.getLangCode());
        entity.setLanguage(template.getLanguage());
        entity.setCompanionEnabled(1);
        entity.setCompanionTemplateId(template.getId());
        entity.setRelationMode(AgentEntity.DEFAULT_RELATION_MODE);
        entity.setScreenExpressionEnabled(1);
        entity.setCameraPreferenceEnabled(1);
        entity.setCreator(userId);
        entity.setCreatedAt(new Date());
        return entity;
    }

    private AgentEntity copy(AgentEntity source) {
        AgentEntity target = new AgentEntity();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private void apply(CompanionProfileSaveDTO dto, AgentEntity entity) {
        if (dto.getAgentName() != null) {
            entity.setAgentName(dto.getAgentName());
        }
        if (dto.getRelationMode() != null) {
            entity.setRelationMode(dto.getRelationMode());
        }
        if (dto.getUserAddress() != null) {
            entity.setUserAddress(dto.getUserAddress());
        }
        if (dto.getPersonality() != null) {
            entity.setPersonality(dto.getPersonality());
        }
        if (dto.getSystemPrompt() != null) {
            entity.setSystemPrompt(dto.getSystemPrompt());
        }
        if (dto.getTtsVoiceId() != null) {
            entity.setTtsVoiceId(dto.getTtsVoiceId());
        }
        if (dto.getCompanionCueConfig() != null) {
            entity.setCompanionCueConfig(dto.getCompanionCueConfig());
        }
        if (dto.getScreenExpressionEnabled() != null) {
            entity.setScreenExpressionEnabled(dto.getScreenExpressionEnabled());
        }
        if (dto.getCameraPreferenceEnabled() != null) {
            entity.setCameraPreferenceEnabled(dto.getCameraPreferenceEnabled());
        }
    }

    private void validateTtsVoice(Long userId, String voiceId, String runtimeTtsModelId, AgentEntity entity,
            boolean syncLanguage) {
        if (voiceId == null || voiceId.isEmpty()) {
            return;
        }
        TimbreEntity timbre = timbreService.selectById(voiceId);
        if (timbre != null) {
            if (runtimeTtsModelId == null || !Objects.equals(runtimeTtsModelId, timbre.getTtsModelId())) {
                throw new RenException("ttsVoiceId 对应音色不属于所选 TTS 模型");
            }
            if (syncLanguage) entity.setTtsLanguage(timbreService.getDefaultLanguage(timbre.getLanguages()));
            return;
        }

        VoiceCloneEntity clone = voiceCloneService.selectById(voiceId);
        if (clone == null) throw new RenException("ttsVoiceId 音色不存在");
        if (!Objects.equals(userId, clone.getUserId())) {
            throw new RenException(ErrorCode.VOICE_RESOURCE_NO_PERMISSION);
        }
        if (runtimeTtsModelId == null || !Objects.equals(runtimeTtsModelId, clone.getModelId())) {
            throw new RenException("ttsVoiceId 对应音色不属于所选 TTS 模型");
        }
        if (!Integer.valueOf(2).equals(clone.getTrainStatus())) {
            throw new RenException("ttsVoiceId 克隆音色尚未训练完成");
        }
        if (syncLanguage) entity.setTtsLanguage(timbreService.getDefaultLanguage(clone.getLanguages()));
    }

    private void validateClearedTtsVoice(String runtimeTtsModelId) {
        if (runtimeTtsModelId == null || runtimeTtsModelId.isBlank()) {
            return;
        }
        if (timbreService.hasTimbresForModel(runtimeTtsModelId)) {
            throw new RenException("请选择声音");
        }
    }

    private CompanionProfileVO toVO(AgentEntity entity) {
        Map<String, String> modelNames = modelConfigService.getModelNamesByIds(
                nonBlankIds(entity.getLlmModelId(), entity.getTtsModelId()));
        Map<String, String> voiceNames = timbreService.getTimbreNamesByIds(nonBlankIds(entity.getTtsVoiceId()));
        return toVO(entity, modelNames, voiceNames);
    }

    private Set<String> nonBlankIds(String... ids) {
        return java.util.Arrays.stream(ids)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
    }

    private CompanionProfileVO toVO(AgentEntity entity, Map<String, String> modelNames,
            Map<String, String> voiceNames) {
        CompanionProfileVO vo = new CompanionProfileVO();
        vo.setId(entity.getId());
        vo.setName(entity.getAgentName());
        vo.setRelationMode(entity.getRelationMode());
        vo.setUserAddress(entity.getUserAddress());
        vo.setPersonality(entity.getPersonality());
        vo.setSystemPrompt(entity.getSystemPrompt());
        vo.setCompanionCueConfig(entity.getCompanionCueConfig());
        vo.setScreenExpressionEnabled(entity.getScreenExpressionEnabled());
        vo.setCameraPreferenceEnabled(entity.getCameraPreferenceEnabled());
        vo.setTemplateId(entity.getCompanionTemplateId());
        vo.setLlmModelId(entity.getLlmModelId());
        vo.setLlmModelName(nameFor(modelNames, entity.getLlmModelId()));
        vo.setTtsModelId(entity.getTtsModelId());
        vo.setTtsModelName(nameFor(modelNames, entity.getTtsModelId()));
        vo.setTtsVoiceId(entity.getTtsVoiceId());
        vo.setTtsVoiceName(nameFor(voiceNames, entity.getTtsVoiceId()));
        vo.setTtsLanguage(entity.getTtsLanguage());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        List<CompanionProfileModelEntity> bindings = profileModelDao.selectByAgentId(entity.getId());
        if (bindings == null) bindings = List.of();
        vo.setModels(editableBindings(entity, bindings));
        List<zixuan.modules.companion.model.vo.CompanionEffectiveModelVO> resolved =
                effectiveModels.resolveForDisplay(entity.getUserId(), entity);
        vo.setEffectiveModels(resolved == null ? List.of() : resolved);
        vo.setActiveVersionNo(entity.getActiveVersionNo());
        vo.setBoundDevices(boundDevices(entity));
        vo.setMemoryPolicy(java.util.Map.of(
                "scope", "device",
                "namespace", "user-agent-device",
                "summaryMemorySource", "device-namespace"));
        vo.setSkills(skillBindings(entity));
        return vo;
    }

    private List<CompanionSkillBindingVO> skillBindings(AgentEntity entity) {
        if (skillBindingDao == null || entity.getActiveVersionNo() == null) return List.of();
        List<AgentVersionSkillBindingEntity> bindings = skillBindingDao.selectEnabledByAgentVersion(
                entity.getId(), entity.getActiveVersionNo());
        if (bindings == null) return List.of();
        return bindings.stream().map(binding -> {
            CompanionSkillBindingVO vo = new CompanionSkillBindingVO();
            vo.setSkillId(binding.getSkillId());
            vo.setVersionMode(binding.getVersionMode());
            vo.setFixedVersion(binding.getFixedVersion());
            vo.setOverrideJson(safeOverrideJson(binding.getOverrideJson()));
            vo.setTriggerPriority(binding.getTriggerPriority());
            vo.setEnabled(Integer.valueOf(1).equals(binding.getEnabled()));
            return vo;
        }).toList();
    }

    private void replaceSkillBindings(String agentId, Integer versionNo,
            List<CompanionSkillBindingSaveDTO> requested) {
        if (skillBindingDao == null || versionNo == null) return;
        skillBindingDao.delete(new QueryWrapper<AgentVersionSkillBindingEntity>()
                .eq("agent_id", agentId).eq("version_no", versionNo));
        if (requested == null) return;
        Date now = new Date();
        for (CompanionSkillBindingSaveDTO item : requested) {
            String mode = item.getVersionMode() == null ? "LATEST" : item.getVersionMode().toUpperCase();
            AgentVersionSkillBindingEntity binding = new AgentVersionSkillBindingEntity();
            binding.setAgentId(agentId);
            binding.setVersionNo(versionNo);
            binding.setSkillId(item.getSkillId());
            binding.setVersionMode(mode);
            binding.setFixedVersion(item.getFixedVersion());
            binding.setOverrideJson(item.getOverrideJson());
            binding.setTriggerPriority(item.getTriggerPriority() == null ? 0 : item.getTriggerPriority());
            binding.setEnabled(Boolean.FALSE.equals(item.getEnabled()) ? 0 : 1);
            binding.setMigrationSource("companion-profile-draft");
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            if (skillBindingDao.insert(binding) != 1) {
                throw new RenException("Skill 绑定保存失败");
            }
        }
    }

    private void validateSkillBindingInputs(List<CompanionSkillBindingSaveDTO> requested) {
        if (requested == null) return;
        for (CompanionSkillBindingSaveDTO item : requested) {
            if (item == null || item.getSkillId() == null || item.getSkillId().isBlank()) {
                throw new RenException("Skill 引用不能为空");
            }
            String mode = item.getVersionMode() == null ? "LATEST" : item.getVersionMode().toUpperCase();
            if (!"LATEST".equals(mode) && !"FIXED".equals(mode)) {
                throw new RenException("Skill 版本策略无效");
            }
            if ("FIXED".equals(mode) && item.getFixedVersion() == null) {
                throw new RenException("固定版本 Skill 必须指定版本号");
            }
        }
    }

    private String safeOverrideJson(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            return SensitiveDataUtils.maskSensitiveFields(new JSONObject(value)).toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private List<CompanionBoundDeviceVO> boundDevices(AgentEntity entity) {
        if (deviceService == null) return List.of();
        List<DeviceEntity> devices = deviceService.getUserDevices(entity.getUserId(), entity.getId());
        if (devices == null) return List.of();
        return devices.stream().map(device -> {
            CompanionBoundDeviceVO vo = new CompanionBoundDeviceVO();
            vo.setId(device.getId());
            vo.setAlias(device.getAlias());
            vo.setMacAddress(device.getMacAddress());
            vo.setBoard(device.getBoard());
            vo.setAppVersion(device.getAppVersion());
            vo.setOnline(device.getLastConnectedAt() != null
                    && device.getLastConnectedAt().after(new Date(System.currentTimeMillis() - 5 * 60 * 1000L)));
            return vo;
        }).toList();
    }

    private List<CompanionProfileModelVO> editableBindings(AgentEntity entity,
            List<CompanionProfileModelEntity> bindings) {
        return EDITABLE_MODEL_TYPES.stream().map(type -> bindings.stream()
                .filter(binding -> type.equals(binding.getModelType()))
                .findFirst()
                .orElseGet(() -> legacyBinding(entity, type)))
                .map(this::bindingDTO)
                .toList();
    }

    private CompanionProfileModelEntity legacyBinding(AgentEntity entity, String type) {
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType(type);
        String resourceId = legacyModelId(entity, type);
        boolean usesDefault = resourceId == null || resourceId.isBlank();
        binding.setSourceType(usesDefault ? "default" : "global");
        binding.setResourceId(usesDefault ? null : resourceId);
        return binding;
    }

    private String legacyModelId(AgentEntity entity, String type) {
        return switch (type) {
            case "LLM" -> entity.getLlmModelId(); case "ASR" -> entity.getAsrModelId();
            case "TTS" -> entity.getTtsModelId(); case "VAD" -> entity.getVadModelId();
            case "VLLM" -> entity.getVllmModelId(); case "Memory" -> entity.getMemModelId();
            default -> null;
        };
    }

    private void validateModelBindings(List<CompanionProfileModelSaveDTO> models, AgentEntity agent,
            List<CompanionProfileModelEntity> existingBindings) {
        for (CompanionProfileModelSaveDTO input : models) {
            if ("private".equals(input.getSource())) {
                throw new RenException("个人模型已停用，请选择系统模型");
            } else if ("global".equals(input.getSource())) {
                var resource = modelConfigService.selectById(input.getResourceId());
                boolean unchanged = unchangedGlobalBinding(input, agent, existingBindings);
                if (!unchanged && (resource == null || !input.getModelType().equals(resource.getModelType())
                        || !Integer.valueOf(1).equals(resource.getIsEnabled()))) {
                    throw new RenException("全局模型类型或状态不匹配");
                }
                setLegacyModelId(agent, input.getModelType(), input.getResourceId());
            } else if ("default".equals(input.getSource())) {
                setLegacyModelId(agent, input.getModelType(), uniqueEnabledDefault(input.getModelType()).getId());
            } else {
                throw new RenException("模型来源不支持");
            }
        }
    }

    private ModelConfigEntity uniqueEnabledDefault(String modelType) {
        List<ModelConfigEntity> enabled = modelConfigService.getEnabledModelsByType(modelType);
        List<ModelConfigEntity> defaults = (enabled == null ? List.<ModelConfigEntity>of() : enabled).stream()
                .filter(model -> model != null
                        && modelType.equals(model.getModelType())
                        && Integer.valueOf(1).equals(model.getIsEnabled())
                        && Integer.valueOf(1).equals(model.getIsDefault()))
                .toList();
        if (defaults.size() != 1) throw new RenException("默认模型不存在或不唯一");
        return defaults.getFirst();
    }

    private boolean ttsBindingChanged(List<CompanionProfileModelSaveDTO> models, AgentEntity current,
            List<CompanionProfileModelEntity> existingBindings) {
        CompanionProfileModelSaveDTO requested = models.stream()
                .filter(binding -> "TTS".equals(binding.getModelType()))
                .findFirst()
                .orElse(null);
        if (requested == null) return false;
        CompanionProfileModelEntity existing = currentTtsBinding(current, existingBindings);
        String requestedResourceId = "default".equals(requested.getSource()) ? null : requested.getResourceId();
        return !Objects.equals(requested.getSource(), existing.getSourceType())
                || !Objects.equals(requestedResourceId, existing.getResourceId());
    }

    private void rematerializeDefaultTtsForVoiceUpdate(AgentEntity current, AgentEntity updated,
            List<CompanionProfileModelEntity> existingBindings) {
        CompanionProfileModelEntity binding = currentTtsBinding(current, existingBindings);
        if ("default".equals(binding.getSourceType())) {
            updated.setTtsModelId(uniqueEnabledDefault("TTS").getId());
        }
    }

    private CompanionProfileModelEntity currentTtsBinding(AgentEntity current,
            List<CompanionProfileModelEntity> existingBindings) {
        return existingBindings.stream()
                .filter(binding -> "TTS".equals(binding.getModelType()))
                .findFirst()
                .orElseGet(() -> legacyBinding(current, "TTS"));
    }

    private boolean unchangedGlobalBinding(CompanionProfileModelSaveDTO input, AgentEntity agent,
            List<CompanionProfileModelEntity> existingBindings) {
        String resourceId = input.getResourceId();
        if (resourceId == null || resourceId.isBlank()) return false;
        var explicit = existingBindings.stream()
                .filter(binding -> input.getModelType().equals(binding.getModelType()))
                .findFirst();
        if (explicit.isPresent()) {
            CompanionProfileModelEntity binding = explicit.get();
            return "global".equals(binding.getSourceType())
                    && resourceId.equals(binding.getResourceId());
        }
        return resourceId.equals(legacyModelId(agent, input.getModelType()));
    }

    private void replaceModelBindings(String agentId, List<CompanionProfileModelSaveDTO> models,
            List<CompanionProfileModelEntity> existingBindings) {
        profileModelDao.deleteByAgentId(agentId);
        for (CompanionProfileModelSaveDTO input : models) {
            CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
            binding.setAgentId(agentId); binding.setModelType(input.getModelType()); binding.setSourceType(input.getSource());
            binding.setResourceId("default".equals(input.getSource()) ? null : input.getResourceId());
            binding.setOverrideJson(input.getOverrides() == null
                    ? unchangedOverride(input, existingBindings)
                    : new JSONObject(input.getOverrides()));
            binding.setCreatedAt(new Date()); binding.setUpdatedAt(new Date());
            if (profileModelDao.insert(binding) != 1) throw new RenException("保存角色模型失败");
        }
    }

    private JSONObject unchangedOverride(CompanionProfileModelSaveDTO input,
            List<CompanionProfileModelEntity> existingBindings) {
        return existingBindings.stream()
                .filter(existing -> Objects.equals(input.getModelType(), existing.getModelType())
                        && Objects.equals(input.getSource(), existing.getSourceType())
                        && Objects.equals(input.getResourceId(), existing.getResourceId()))
                .findFirst()
                .map(CompanionProfileModelEntity::getOverrideJson)
                .orElse(null);
    }

    private CompanionProfileModelVO bindingDTO(CompanionProfileModelEntity binding) {
        CompanionProfileModelVO dto = new CompanionProfileModelVO();
        dto.setModelType(binding.getModelType()); dto.setSource(binding.getSourceType()); dto.setResourceId(binding.getResourceId());
        dto.setOverrides(binding.getOverrideJson());
        boolean privateBinding = "private".equals(binding.getSourceType());
        if (privateBinding) {
            dto.setName(privateDisplayName(binding));
            dto.setEnabled(false);
            dto.setUnavailableReason("旧个人模型已停用，请重新选择");
        } else if ("global".equals(binding.getSourceType())) {
            var model = modelConfigService.selectById(binding.getResourceId());
            dto.setName(model == null ? "未知模型" : nameOrDefault(model.getModelName(), "系统模型"));
            if (model == null) {
                dto.setEnabled(false);
                dto.setUnavailableReason("模型不存在，请重新选择");
            } else if (!java.util.Objects.equals(binding.getModelType(), model.getModelType())) {
                dto.setEnabled(false);
                dto.setUnavailableReason("模型类型不匹配，请重新选择");
            } else if (!Integer.valueOf(1).equals(model.getIsEnabled())) {
                dto.setEnabled(false);
                dto.setUnavailableReason("模型已停用，请重新选择");
            } else {
                dto.setEnabled(true);
            }
        } else {
            dto.setEnabled(true);
        }
        return dto;
    }

    private String nameOrDefault(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private String privateDisplayName(CompanionProfileModelEntity binding) {
        JSONObject overrides = binding.getOverrideJson();
        if (overrides == null) return "旧个人模型";
        String displayName = overrides.getStr("displayName");
        if (displayName == null || displayName.isBlank()) displayName = overrides.getStr("name");
        return displayName == null || displayName.isBlank() ? "旧个人模型" : displayName;
    }

    private void setLegacyModelId(AgentEntity agent, String type, String id) {
        switch (type) {
            case "LLM" -> agent.setLlmModelId(id); case "ASR" -> agent.setAsrModelId(id); case "TTS" -> agent.setTtsModelId(id);
            case "VAD" -> agent.setVadModelId(id); case "VLLM" -> agent.setVllmModelId(id); case "Memory" -> agent.setMemModelId(id);
            default -> throw new RenException("模型类型不支持");
        }
    }

    private String nameFor(Map<String, String> names, String id) {
        return id == null ? null : names.get(id);
    }

}
