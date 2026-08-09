package xiaozhi.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.model.dao.CompanionPrivateModelDao;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.entity.CompanionProfileModelEntity;
import xiaozhi.modules.companion.model.service.CompanionEffectiveModelService;
import xiaozhi.modules.companion.model.service.CompanionGlobalModelCredentialService;
import xiaozhi.modules.companion.model.service.CompanionModelCatalogService;
import xiaozhi.modules.companion.model.service.CompanionModelPresetService;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.model.vo.CompanionEffectiveModelVO;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;
import xiaozhi.modules.companion.model.vo.GlobalModelCredentialRuntime;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

@Service
@AllArgsConstructor
public class CompanionEffectiveModelServiceImpl implements CompanionEffectiveModelService {
    public static final List<String> MODEL_TYPES = List.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private final CompanionPrivateModelDao privateDao;
    private final CompanionProfileModelDao bindingDao;
    private final ModelConfigService globalModels;
    private final CompanionModelSecretService secrets;
    private final CompanionModelCatalogService catalog;
    private final CompanionGlobalModelCredentialService globalCredentials;
    private final CompanionModelPresetService presets;

    @Override
    public List<CompanionEffectiveModelVO> resolveForDisplay(Long userId, AgentEntity profile) {
        Map<String, CompanionProfileModelEntity> bindings = new LinkedHashMap<>();
        for (CompanionProfileModelEntity binding : bindingDao.selectByAgentId(profile.getId())) {
            bindings.put(binding.getModelType(), binding);
        }
        List<CompanionEffectiveModelVO> result = new ArrayList<>();
        for (String type : MODEL_TYPES) {
            CompanionEffectiveModelVO resolved = resolveOne(userId, profile, type, bindings.get(type));
            if (resolved != null) result.add(resolved);
        }
        return result;
    }

    @Override
    public List<CompanionModelOptionVO> options(Long userId) {
        List<CompanionModelOptionVO> result = new ArrayList<>();
        for (String type : MODEL_TYPES) {
            globalModels.getEnabledModelsByType(type).forEach(model -> {
                JSONObject config = model.getConfigJson();
                String providerCode = config == null ? null : config.getStr("type");
                CompanionModelOptionVO option = new CompanionModelOptionVO(
                        model.getId(), model.getModelType(), model.getModelName(), "global",
                        StringUtils.defaultIfBlank(providerCode, model.getModelCode()), true);
                option.setIsDefault(Integer.valueOf(1).equals(model.getIsDefault()));
                option.setCredentialStatus("not_required");
                result.add(option);
            });
        }
        return result;
    }

    @Override
    public Map<String, CompanionRuntimeModel> resolveRuntime(Long userId, AgentEntity profile) {
        Map<String, CompanionRuntimeModel> result = new LinkedHashMap<>();
        for (CompanionProfileModelEntity binding : bindingDao.selectByAgentId(profile.getId())) {
            if ("global".equals(binding.getSourceType())) {
                appendGlobalRuntime(userId, binding, result);
            }
        }
        return result;
    }

    private void appendGlobalRuntime(Long userId, CompanionProfileModelEntity binding,
            Map<String, CompanionRuntimeModel> result) {
        ModelConfigEntity resource = globalModels.selectById(binding.getResourceId());
        if (resource == null || !Integer.valueOf(1).equals(resource.getIsEnabled())
                || !Objects.equals(binding.getModelType(), resource.getModelType())
                || !catalog.isSelectable(userId, resource.getId())) return;
        GlobalModelCredentialRuntime userConfig = globalCredentials.runtime(userId, resource.getId());
        JSONObject config = resource.getConfigJson() == null
                ? new JSONObject() : new JSONObject(resource.getConfigJson());
        for (String key : presets.credentialKeys(resource.getId())) config.remove(key);
        if (StringUtils.isNotBlank(userConfig.apiUrl())) {
            config.set("TTS".equals(resource.getModelType()) ? "api_url" : "base_url", userConfig.apiUrl());
        }
        if (StringUtils.isNotBlank(userConfig.modelId())) {
            config.set("TTS".equals(resource.getModelType()) ? "model" : "model_name", userConfig.modelId());
        }
        userConfig.secrets().forEach(config::set);
        if (binding.getOverrideJson() != null) binding.getOverrideJson().forEach(config::set);
        if (config.containsKey("model")) config.set("model_name", config.get("model"));
        result.put(resource.getModelType(), new CompanionRuntimeModel("global:" + resource.getId(), config));
    }

    private CompanionEffectiveModelVO resolveOne(Long userId, AgentEntity profile, String type,
            CompanionProfileModelEntity binding) {
        if (binding != null && "private".equals(binding.getSourceType())) {
            CompanionEffectiveModelVO vo = base(type, binding.getResourceId(), privateDisplayName(binding), "private");
            vo.setEnabled(false);
            vo.setUnavailableReason("旧个人模型已停用，请重新选择");
            vo.setOverrides(Map.of());
            return vo;
        }
        String id = binding != null && "global".equals(binding.getSourceType()) ? binding.getResourceId() : legacyId(profile, type);
        String source = binding != null && "global".equals(binding.getSourceType()) ? "global" : "default";
        ModelConfigEntity model = StringUtils.isBlank(id) ? defaultModel(type) : globalModels.selectById(id);
        if (model == null && StringUtils.isBlank(id)) return null;
        CompanionEffectiveModelVO vo = base(type, model == null ? id : model.getId(),
                model == null ? "未知模型" : StringUtils.defaultIfBlank(model.getModelName(), "系统模型"), source);
        JSONObject overrides = binding == null ? null : binding.getOverrideJson();
        vo.setOverrides(overrides == null ? Map.of() : new LinkedHashMap<>(overrides));
        vo.setOverridden(overrides != null && !overrides.isEmpty());
        if (overrides != null) vo.setModelId(overrides.getStr("model"));
        if (model == null) {
            vo.setEnabled(false);
            vo.setUnavailableReason("模型不存在，请重新选择");
        } else if (!type.equals(model.getModelType())) {
            vo.setEnabled(false);
            vo.setUnavailableReason("模型类型不匹配，请重新选择");
        } else if (!Integer.valueOf(1).equals(model.getIsEnabled())) {
            vo.setEnabled(false);
            vo.setUnavailableReason("模型已停用，请重新选择");
        } else {
            vo.setEnabled(true);
        }
        return vo;
    }

    private String privateDisplayName(CompanionProfileModelEntity binding) {
        JSONObject overrides = binding.getOverrideJson();
        if (overrides == null) return "旧个人模型";
        return StringUtils.defaultIfBlank(overrides.getStr("displayName"),
                StringUtils.defaultIfBlank(overrides.getStr("name"), "旧个人模型"));
    }

    private ModelConfigEntity defaultModel(String type) {
        return globalModels.getEnabledModelsByType(type).stream()
                .filter(model -> Integer.valueOf(1).equals(model.getIsDefault())).findFirst().orElse(null);
    }

    private String legacyId(AgentEntity profile, String type) {
        return switch (type) {
            case "LLM" -> profile.getLlmModelId(); case "ASR" -> profile.getAsrModelId();
            case "TTS" -> profile.getTtsModelId(); case "VAD" -> profile.getVadModelId();
            case "VLLM" -> profile.getVllmModelId(); case "Memory" -> profile.getMemModelId();
            default -> null;
        };
    }

    private CompanionEffectiveModelVO base(String type, String id, String name, String source) {
        CompanionEffectiveModelVO vo = new CompanionEffectiveModelVO();
        vo.setModelType(type); vo.setResourceId(id); vo.setName(name); vo.setSource(source); return vo;
    }
}
