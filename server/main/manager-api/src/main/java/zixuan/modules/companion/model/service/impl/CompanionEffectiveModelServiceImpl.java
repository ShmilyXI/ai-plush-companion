package zixuan.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.model.dao.CompanionPrivateModelDao;
import zixuan.modules.companion.model.dao.CompanionProfileModelDao;
import zixuan.modules.companion.model.entity.CompanionProfileModelEntity;
import zixuan.modules.companion.model.service.CompanionEffectiveModelService;
import zixuan.modules.companion.model.service.CompanionGlobalModelCredentialService;
import zixuan.modules.companion.model.service.CompanionModelCatalogService;
import zixuan.modules.companion.model.service.CompanionModelPresetService;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.companion.model.vo.CompanionEffectiveModelVO;
import zixuan.modules.companion.model.vo.CompanionModelOptionVO;
import zixuan.modules.companion.model.vo.CompanionRuntimeModel;
import zixuan.modules.companion.model.vo.GlobalModelCredentialRuntime;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;

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
                var credential = globalCredentials.get(userId, model.getId());
                String status = credential == null ? "unknown" : credential.getCredentialStatus();
                boolean credentialReady = credential != null && credential.isCredentialConfigured();
                if (!credentialReady && legacyConfigHasRequiredCredentials(model)) {
                    status = "configured";
                    credentialReady = true;
                }
                option.setCredentialStatus(status);
                option.setEnabled(credentialReady);
                if (!credentialReady && "missing".equals(status)) option.setUnavailableReason("请先在模型管理中配置凭据");
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

    @Override
    public Map<String, CompanionRuntimeModel> resolveRuntimeForPlayground(Long userId, AgentEntity profile,
            Map<String, String> selectedModelIds) {
        Map<String, CompanionProfileModelEntity> bindings = new LinkedHashMap<>();
        for (CompanionProfileModelEntity binding : bindingDao.selectByAgentId(profile.getId())) {
            bindings.put(binding.getModelType(), binding);
        }
        Map<String, CompanionRuntimeModel> result = new LinkedHashMap<>();
        for (String type : MODEL_TYPES) {
            String selectedId = selectedModelIds == null ? null : selectedModelIds.get(type);
            CompanionProfileModelEntity binding = bindings.get(type);
            if (StringUtils.isBlank(selectedId) && binding != null && "global".equals(binding.getSourceType())) {
                selectedId = binding.getResourceId();
            }
            if (StringUtils.isBlank(selectedId)) selectedId = legacyId(profile, type);
            if (StringUtils.isBlank(selectedId)) {
                ModelConfigEntity defaultModel = defaultModel(type);
                selectedId = defaultModel == null ? null : defaultModel.getId();
            }
            if (StringUtils.isNotBlank(selectedId)) {
                appendGlobalRuntime(userId, type, selectedId,
                        binding != null && "global".equals(binding.getSourceType()) ? binding.getOverrideJson() : null,
                        result, true);
            }
        }
        return result;
    }

    private void appendGlobalRuntime(Long userId, CompanionProfileModelEntity binding,
            Map<String, CompanionRuntimeModel> result) {
        appendGlobalRuntime(userId, binding.getModelType(), binding.getResourceId(), binding.getOverrideJson(), result, false);
    }

    private void appendGlobalRuntime(Long userId, String expectedType, String resourceId,
            JSONObject overrides, Map<String, CompanionRuntimeModel> result, boolean playgroundFallback) {
        // Runtime resolution needs the private provider credential. selectById is
        // intentionally redacted for management responses, so prefer the raw
        // cache-backed lookup and retain the fallback for older test adapters.
        ModelConfigEntity resource = globalModels.getModelByIdFromCache(resourceId);
        if (resource == null) resource = globalModels.selectById(resourceId);
        if (resource == null || !Integer.valueOf(1).equals(resource.getIsEnabled())
                || !Objects.equals(expectedType, resource.getModelType())) return;
        boolean selectable = catalog.isSelectable(userId, resource.getId());
        boolean useLegacyCredentials = !selectable && playgroundFallback && legacyConfigHasRequiredCredentials(resource);
        if (!selectable && !useLegacyCredentials) return;
        GlobalModelCredentialRuntime userConfig = globalCredentials.runtime(userId, resource.getId());
        JSONObject config = resource.getConfigJson() == null
                ? new JSONObject() : new JSONObject(resource.getConfigJson());
        if (!useLegacyCredentials) {
            for (String key : presets.credentialKeys(resource.getId())) config.remove(key);
        }
        if (StringUtils.isNotBlank(userConfig.apiUrl())) {
            config.set("TTS".equals(resource.getModelType()) ? "api_url" : "base_url", userConfig.apiUrl());
        }
        if (StringUtils.isNotBlank(userConfig.modelId())) {
            config.set("TTS".equals(resource.getModelType()) ? "model" : "model_name", userConfig.modelId());
        }
        userConfig.secrets().forEach(config::set);
        if (overrides != null) overrides.forEach(config::set);
        if (config.containsKey("model")) config.set("model_name", config.get("model"));
        result.put(resource.getModelType(), new CompanionRuntimeModel("global:" + resource.getId(), config));
    }

    private boolean legacyConfigHasRequiredCredentials(ModelConfigEntity model) {
        Set<String> keys = presets.credentialKeys(model.getId());
        JSONObject config = model.getConfigJson();
        if (config == null) return false;
        if (keys.isEmpty()) {
            return List.of("api_key", "access_token", "access_key", "access_key_id", "secret_key",
                    "secret_id", "appid", "app_id", "authorization", "token").stream()
                    .anyMatch(key -> usableLegacySecret(config.get(key)));
        }
        return keys.stream().allMatch(key -> usableLegacySecret(config.get(key)));
    }

    private boolean usableLegacySecret(Object value) {
        if (!(value instanceof String text) || StringUtils.isBlank(text)) return false;
        String normalized = text.trim().toLowerCase(java.util.Locale.ROOT);
        return !normalized.contains("你的") && !normalized.contains("your_")
                && !normalized.contains("your ") && !normalized.contains("placeholder")
                && !normalized.contains("todo");
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
