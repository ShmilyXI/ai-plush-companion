package xiaozhi.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.model.dao.CompanionPrivateModelDao;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.dto.CompanionPrivateModelSaveDTO;
import xiaozhi.modules.companion.model.entity.CompanionPrivateModelEntity;
import xiaozhi.modules.companion.model.service.CompanionModelCatalogService;
import xiaozhi.modules.companion.model.service.CompanionGlobalModelCredentialService;
import xiaozhi.modules.companion.model.service.CompanionModelPresetService;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.model.service.CompanionModelTemplateService;
import xiaozhi.modules.companion.model.service.CompanionPrivateModelService;
import xiaozhi.modules.companion.model.vo.CompanionModelCatalogItemVO;
import xiaozhi.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import xiaozhi.modules.companion.model.vo.CompanionModelPresetVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.companion.model.vo.CompanionPrivateModelVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

@Service
@AllArgsConstructor
public class CompanionModelCatalogServiceImpl implements CompanionModelCatalogService {
    private static final Set<String> MODEL_TYPES = Set.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private static final Set<String> COMMON_KEYS = Set.of("type", "api_url", "base_url", "url", "model", "model_name");
    private final ModelConfigService globalModels;
    private final CompanionPrivateModelDao privateModels;
    private final CompanionProfileModelDao bindings;
    private final CompanionPrivateModelService privateService;
    private final CompanionModelTemplateService templates;
    private final CompanionModelPresetService presets;
    private final CompanionGlobalModelCredentialService credentials;
    private final CompanionModelSecretService secrets;

    @Override
    public List<CompanionModelCatalogItemVO> management(Long userId, String modelType) {
        requireType(modelType);
        List<CompanionModelCatalogItemVO> result = new ArrayList<>();
        for (ModelConfigEntity model : globalModels.getModelsByType(modelType)) result.add(globalItem(userId, model, false));
        for (CompanionPrivateModelEntity model : privateModels.selectOwnedByType(userId, modelType)) {
            result.add(privateItem(model));
        }
        return result;
    }

    @Override
    public List<CompanionModelCatalogItemVO> selection(Long userId, String modelType) {
        requireType(modelType);
        List<CompanionModelCatalogItemVO> result = new ArrayList<>();
        for (ModelConfigEntity model : globalModels.getModelsByType(modelType)) {
            if (Integer.valueOf(1).equals(model.getIsEnabled())) {
                CompanionModelCatalogItemVO item = globalItem(userId, model, true);
                item.setActions(List.of());
                result.add(item);
            }
        }
        for (CompanionPrivateModelEntity model : privateModels.selectOwnedByType(userId, modelType)) {
            if (Integer.valueOf(1).equals(model.getEnabled())) {
                CompanionModelCatalogItemVO item = privateItem(model);
                if ("missing".equals(item.getCredentialStatus())) {
                    item.setEnabled(false);
                    item.setUnavailableReason("请先在模型管理中配置凭据");
                }
                item.setActions(List.of());
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public boolean isSelectable(Long userId, String globalModelId) {
        ModelConfigEntity model = globalModels.selectById(globalModelId);
        if (model == null || !Integer.valueOf(1).equals(model.getIsEnabled())) return false;
        return selectable(credentials.get(userId, globalModelId).getCredentialStatus());
    }

    @Override
    public CompanionPrivateModelVO copy(Long userId, String reference, String requestedName) {
        SourceReference source = parseReference(reference);
        CompanionPrivateModelSaveDTO request = "global".equals(source.source())
                ? copyGlobal(source.id(), requestedName)
                : copyPrivate(userId, source.id(), requestedName);
        return privateService.create(userId, request);
    }

    private CompanionPrivateModelSaveDTO copyGlobal(String id, String requestedName) {
        ModelConfigEntity source = globalModels.selectById(id);
        if (source == null) throw new RenException("系统模型不存在");
        JSONObject config = source.getConfigJson() == null ? new JSONObject() : source.getConfigJson();
        String providerCode = StringUtils.defaultIfBlank(config.getStr("type"), source.getModelCode());
        CompanionModelPresetVO preset = presets.get(id);
        CompanionPrivateModelSaveDTO request = baseRequest(source.getModelType(), copyName(source.getModelName(), requestedName), providerCode);
        request.setVendorName(StringUtils.defaultIfBlank(preset.getVendorName(), source.getModelName()));
        request.setProtocol(StringUtils.defaultIfBlank(preset.getProtocol(), providerCode));
        request.setCredentialRequired(!"not_required".equals(preset.getCredentialRequirement()));
        request.setProviderTemplateId(templateId(source.getModelType(), providerCode));
        request.setApiUrl(first(config, "api_url", "base_url", "url"));
        request.setModelId(first(config, "model", "model_name"));
        request.setConfig(publicConfig(config));
        return request;
    }

    private CompanionPrivateModelSaveDTO copyPrivate(Long userId, String id, String requestedName) {
        CompanionPrivateModelEntity source = privateService.requireOwned(userId, id);
        CompanionPrivateModelSaveDTO request = baseRequest(source.getModelType(), copyName(source.getName(), requestedName),
                source.getProviderCode());
        request.setVendorName(StringUtils.defaultIfBlank(source.getVendorName(), source.getProviderCode()));
        request.setProtocol(StringUtils.defaultIfBlank(source.getProtocol(), source.getProviderCode()));
        request.setCredentialRequired(!Integer.valueOf(0).equals(source.getCredentialRequired()));
        request.setProviderTemplateId(source.getProviderTemplateId());
        request.setApiUrl(source.getApiUrl());
        request.setModelId(source.getModelId());
        request.setConfig(source.getConfigJson() == null ? Map.of() : new LinkedHashMap<>(source.getConfigJson()));
        return request;
    }

    private CompanionPrivateModelSaveDTO baseRequest(String modelType, String name, String providerCode) {
        CompanionPrivateModelSaveDTO request = new CompanionPrivateModelSaveDTO();
        request.setModelType(modelType);
        request.setName(name);
        request.setProviderCode(providerCode);
        request.setEnabled(1);
        return request;
    }

    private Map<String, Object> publicConfig(JSONObject config) {
        Map<String, Object> result = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (!COMMON_KEYS.contains(key) && !templates.isSecretKey(key)) result.put(key, value);
        });
        return result;
    }

    private CompanionModelCatalogItemVO globalItem(Long userId, ModelConfigEntity model, boolean selection) {
        CompanionModelCatalogItemVO item = base(model.getId(), model.getModelType(), model.getModelName(), "global");
        JSONObject config = model.getConfigJson();
        String providerCode = config == null ? null : config.getStr("type");
        CompanionModelPresetVO preset = presets.get(model.getId());
        CompanionGlobalModelCredentialVO credential = credentials.get(userId, model.getId());
        item.setProviderCode(StringUtils.defaultIfBlank(providerCode, model.getModelCode()));
        item.setVendorCode(preset.getVendorCode());
        item.setVendorName(preset.getVendorName());
        item.setProtocol(preset.getProtocol());
        item.setProviderTemplateId(templateId(model.getModelType(), item.getProviderCode()));
        item.setApiUrl(firstNonBlank(credential.getApiUrl(), preset.getDefaultApiUrl(),
                config == null ? null : first(config, "base_url", "api_url", "url")));
        item.setModelId(firstNonBlank(credential.getModelId(),
                config == null ? null : first(config, "model", "model_name")));
        item.setCredentialRequirement(preset.getCredentialRequirement());
        item.setCredentialConfigured(credential.isCredentialConfigured());
        item.setCredentialStatus(credential.getCredentialStatus());
        item.setKeyUrl(preset.getKeyUrl());
        item.setDocsUrl(preset.getDocsUrl());
        item.setSetupGuide(preset.getSetupGuide());
        item.setCredentialFields(preset.getCredentialFields());
        boolean enabled = Integer.valueOf(1).equals(model.getIsEnabled());
        if (selection && !selectable(item.getCredentialStatus())) {
            enabled = false;
            item.setUnavailableReason("请先在模型管理中配置凭据");
        }
        item.setEnabled(enabled);
        item.setDefaultModel(Integer.valueOf(1).equals(model.getIsDefault()));
        item.setUsageCount(bindings.countResourceUsage("global", model.getId()));
        item.setActions(List.of("view", "configure", "test", "copy"));
        return item;
    }

    private CompanionModelCatalogItemVO privateItem(CompanionPrivateModelEntity model) {
        CompanionModelCatalogItemVO item = base(model.getId(), model.getModelType(), model.getName(), "private");
        item.setProviderCode(model.getProviderCode());
        item.setVendorCode(model.getProviderCode());
        item.setVendorName(StringUtils.defaultIfBlank(model.getVendorName(), model.getProviderCode()));
        item.setProtocol(model.getProtocol());
        item.setProviderTemplateId(model.getProviderTemplateId());
        item.setApiUrl(model.getApiUrl());
        item.setModelId(model.getModelId());
        boolean required = !Integer.valueOf(0).equals(model.getCredentialRequired());
        Map<String, Object> savedSecrets = secrets.decryptMap(model.getSecretConfigCiphertext());
        boolean configured = StringUtils.isNotBlank(model.getApiKeyCiphertext())
                || savedSecrets != null && !savedSecrets.isEmpty();
        item.setCredentialRequirement(required ? "required" : "not_required");
        item.setCredentialConfigured(!required || configured);
        item.setCredentialStatus(required ? configured ? "configured" : "missing" : "not_required");
        item.setSetupGuide(List.of());
        item.setCredentialFields(List.of());
        item.setEnabled(Integer.valueOf(1).equals(model.getEnabled()));
        item.setUsageCount(bindings.countResourceUsage("private", model.getId()));
        item.setActions(item.isEnabled()
                ? List.of("edit", "copy", "test", "disable", "delete")
                : List.of("edit", "copy", "test", "enable", "delete"));
        return item;
    }

    private CompanionModelCatalogItemVO base(String id, String modelType, String name, String source) {
        CompanionModelCatalogItemVO item = new CompanionModelCatalogItemVO();
        item.setId(id);
        item.setReference(source + ":" + id);
        item.setModelType(modelType);
        item.setName(name);
        item.setSource(source);
        return item;
    }

    private String templateId(String modelType, String providerCode) {
        if (StringUtils.isBlank(providerCode)) return null;
        return templates.list(modelType).stream().filter(template -> providerCode.equals(template.getProviderCode()))
                .map(CompanionModelProviderTemplateVO::getId).findFirst().orElse(null);
    }

    private String first(JSONObject config, String... keys) {
        for (String key : keys) {
            String value = config.getStr(key);
            if (StringUtils.isNotBlank(value)) return value;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.isNotBlank(value)) return value;
        return null;
    }

    private boolean selectable(String credentialStatus) {
        return "configured".equals(credentialStatus) || "not_required".equals(credentialStatus);
    }

    private String copyName(String current, String requested) {
        String value = StringUtils.defaultIfBlank(StringUtils.trimToNull(requested), current + " 复制");
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private SourceReference parseReference(String reference) {
        int separator = reference == null ? -1 : reference.indexOf(':');
        if (separator < 1 || separator == reference.length() - 1) throw new RenException("模型引用无效");
        String source = reference.substring(0, separator);
        if (!"global".equals(source) && !"private".equals(source)) throw new RenException("模型来源无效");
        return new SourceReference(source, reference.substring(separator + 1));
    }

    private void requireType(String modelType) {
        if (!MODEL_TYPES.contains(modelType)) throw new RenException("模型类型不支持");
    }

    private record SourceReference(String source, String id) {}
}
