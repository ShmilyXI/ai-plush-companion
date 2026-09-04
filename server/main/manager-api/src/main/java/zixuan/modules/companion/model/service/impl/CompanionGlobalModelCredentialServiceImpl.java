package zixuan.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.model.dao.CompanionGlobalModelCredentialDao;
import zixuan.modules.companion.model.dto.CompanionGlobalModelCredentialSaveDTO;
import zixuan.modules.companion.model.entity.CompanionGlobalModelCredentialEntity;
import zixuan.modules.companion.model.service.CompanionGlobalModelCredentialService;
import zixuan.modules.companion.model.service.CompanionModelConnectionTester;
import zixuan.modules.companion.model.service.CompanionModelPresetService;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import zixuan.modules.companion.model.vo.CompanionModelPresetVO;
import zixuan.modules.companion.model.vo.CompanionModelProviderFieldVO;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.companion.model.vo.GlobalModelCredentialRuntime;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;

@Service
@AllArgsConstructor
public class CompanionGlobalModelCredentialServiceImpl implements CompanionGlobalModelCredentialService {
    private final CompanionGlobalModelCredentialDao dao;
    private final ModelConfigService globalModels;
    private final CompanionModelPresetService presets;
    private final CompanionModelSecretService secrets;
    private final CompanionModelConnectionTester tester;

    @Override
    public CompanionGlobalModelCredentialVO get(Long userId, String globalModelId) {
        requireGlobal(globalModelId);
        CompanionModelPresetVO preset = presets.get(globalModelId);
        return toVO(globalModelId, dao.selectOwned(userId, globalModelId), preset);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionGlobalModelCredentialVO save(Long userId, String globalModelId,
            CompanionGlobalModelCredentialSaveDTO dto) {
        requireGlobal(globalModelId);
        CompanionModelPresetVO preset = presets.get(globalModelId);
        CompanionGlobalModelCredentialEntity existing = dao.selectOwned(userId, globalModelId);
        Map<String, Object> merged = mergeSecrets(existing, dto, preset);

        Date now = new Date();
        CompanionGlobalModelCredentialEntity entity = existing == null
                ? new CompanionGlobalModelCredentialEntity() : existing;
        if (existing == null) {
            entity.setId(UUID.randomUUID().toString().replace("-", ""));
            entity.setUserId(userId);
            entity.setGlobalModelId(globalModelId);
            entity.setCreatedAt(now);
        }
        entity.setApiUrlOverride(StringUtils.trimToNull(dto.getApiUrl()));
        entity.setModelIdOverride(StringUtils.trimToNull(dto.getModelId()));
        entity.setSecretConfigCiphertext(secrets.encryptMap(merged));
        entity.setUpdatedAt(now);
        if (dao.upsert(entity) < 1) throw new RenException("系统模型配置保存失败");
        return toVO(globalModelId, entity, preset);
    }

    @Override
    public CompanionModelTestVO test(Long userId, String globalModelId,
            CompanionGlobalModelCredentialSaveDTO dto) {
        ModelConfigEntity global = requireGlobal(globalModelId);
        CompanionModelPresetVO preset = presets.get(globalModelId);
        CompanionGlobalModelCredentialEntity saved = dao.selectOwned(userId, globalModelId);
        Map<String, Object> runtime = publicRuntime(global, preset);
        String apiUrl = firstNonBlank(dto.getApiUrl(), saved == null ? null : saved.getApiUrlOverride());
        String modelId = firstNonBlank(dto.getModelId(), saved == null ? null : saved.getModelIdOverride());
        applyOverrides(runtime, global.getModelType(), apiUrl, modelId);
        runtime.putAll(mergeSecrets(saved, dto, preset));
        return tester.test(providerCode(global), runtime);
    }

    @Override
    public GlobalModelCredentialRuntime runtime(Long userId, String globalModelId) {
        requireGlobal(globalModelId);
        CompanionGlobalModelCredentialEntity entity = dao.selectOwned(userId, globalModelId);
        if (entity == null) return new GlobalModelCredentialRuntime(null, null, Map.of());
        return new GlobalModelCredentialRuntime(entity.getApiUrlOverride(), entity.getModelIdOverride(),
                Map.copyOf(secrets.decryptMap(entity.getSecretConfigCiphertext())));
    }

    private ModelConfigEntity requireGlobal(String globalModelId) {
        ModelConfigEntity global = globalModels.selectById(globalModelId);
        if (global == null) throw new RenException("系统模型不存在");
        return global;
    }

    private Map<String, Object> mergeSecrets(CompanionGlobalModelCredentialEntity saved,
            CompanionGlobalModelCredentialSaveDTO dto, CompanionModelPresetVO preset) {
        Set<String> allowed = preset.getCredentialFields().stream()
                .map(CompanionModelProviderFieldVO::getKey).collect(java.util.stream.Collectors.toSet());
        Map<String, Object> result = saved == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(secrets.decryptMap(saved.getSecretConfigCiphertext()));
        List<String> clearKeys = dto.getClearSecretKeys() == null ? List.of() : dto.getClearSecretKeys();
        for (String key : clearKeys) {
            requireAllowedKey(key, allowed);
            result.remove(key);
        }
        Map<String, Object> submitted = dto.getSecrets() == null ? Map.of() : dto.getSecrets();
        submitted.forEach((key, value) -> {
            requireAllowedKey(key, allowed);
            Object normalized = normalizeSecret(value);
            if (normalized != null) result.put(key, normalized);
        });
        result.keySet().retainAll(allowed);
        return result;
    }

    private void requireAllowedKey(String key, Set<String> allowed) {
        if (StringUtils.isBlank(key) || !allowed.contains(key)) {
            throw new RenException("系统模型凭据字段无效");
        }
    }

    private Object normalizeSecret(Object value) {
        if (value instanceof String text) return StringUtils.trimToNull(text);
        return value;
    }

    private CompanionGlobalModelCredentialVO toVO(String globalModelId,
            CompanionGlobalModelCredentialEntity entity, CompanionModelPresetVO preset) {
        Map<String, Object> stored = entity == null ? Map.of()
                : secrets.decryptMap(entity.getSecretConfigCiphertext());
        List<String> configured = new ArrayList<>();
        for (CompanionModelProviderFieldVO field : preset.getCredentialFields()) {
            if (!isMissing(stored.get(field.getKey()))) configured.add(field.getKey());
        }
        String status = credentialStatus(preset, Set.copyOf(configured));
        CompanionGlobalModelCredentialVO result = new CompanionGlobalModelCredentialVO();
        result.setGlobalModelId(globalModelId);
        result.setApiUrl(entity == null ? null : entity.getApiUrlOverride());
        result.setModelId(entity == null ? null : entity.getModelIdOverride());
        result.setConfiguredSecretKeys(List.copyOf(configured));
        result.setCredentialRequirement(preset.getCredentialRequirement());
        result.setCredentialConfigured("configured".equals(status) || "not_required".equals(status));
        result.setCredentialStatus(status);
        return result;
    }

    private String credentialStatus(CompanionModelPresetVO preset, Set<String> configured) {
        if ("not_required".equals(preset.getCredentialRequirement())) return "not_required";
        if (!"required".equals(preset.getCredentialRequirement())) return "unknown";
        boolean complete = preset.getCredentialFields().stream()
                .filter(CompanionModelProviderFieldVO::isRequired)
                .allMatch(field -> configured.contains(field.getKey()));
        return complete ? "configured" : "missing";
    }

    private Map<String, Object> publicRuntime(ModelConfigEntity global, CompanionModelPresetVO preset) {
        Map<String, Object> result = global.getConfigJson() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(global.getConfigJson());
        for (CompanionModelProviderFieldVO field : preset.getCredentialFields()) result.remove(field.getKey());
        return result;
    }

    private void applyOverrides(Map<String, Object> runtime, String modelType, String apiUrl, String modelId) {
        if (StringUtils.isNotBlank(apiUrl)) {
            runtime.put("TTS".equals(modelType) ? "api_url" : "base_url", apiUrl.trim());
        }
        if (StringUtils.isNotBlank(modelId)) {
            runtime.put("TTS".equals(modelType) ? "model" : "model_name", modelId.trim());
        }
    }

    private String providerCode(ModelConfigEntity global) {
        JSONObject config = global.getConfigJson();
        return config == null ? global.getModelCode()
                : StringUtils.defaultIfBlank(config.getStr("type"), global.getModelCode());
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.isNotBlank(preferred) ? preferred : fallback;
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && StringUtils.isBlank(text);
    }
}
