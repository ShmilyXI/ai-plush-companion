package xiaozhi.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.model.dao.CompanionPrivateModelDao;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.dto.CompanionPrivateModelSaveDTO;
import xiaozhi.modules.companion.model.entity.CompanionPrivateModelEntity;
import xiaozhi.modules.companion.model.service.CompanionModelConnectionTester;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.model.service.CompanionModelTemplateService;
import xiaozhi.modules.companion.model.service.CompanionPrivateModelService;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderFieldVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.companion.model.vo.CompanionPrivateModelVO;
import xiaozhi.modules.companion.service.CompanionAuditService;

@Service
@AllArgsConstructor
public class CompanionPrivateModelServiceImpl implements CompanionPrivateModelService {
    private static final Set<String> TYPES = Set.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private final CompanionPrivateModelDao dao;
    private final CompanionProfileModelDao bindingDao;
    private final CompanionModelSecretService secrets;
    private final CompanionAuditService audit;
    private final CompanionModelTemplateService templates;
    private final CompanionModelConnectionTester tester;

    @Override
    public List<CompanionPrivateModelVO> list(Long userId, String modelType) {
        requireType(modelType);
        return dao.selectOwnedByType(userId, modelType).stream().map(this::toVO).toList();
    }

    @Override
    public CompanionPrivateModelVO get(Long userId, String id) { return toVO(requireOwned(userId, id)); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionPrivateModelVO create(Long userId, CompanionPrivateModelSaveDTO dto) {
        requireType(dto.getModelType());
        CompanionPrivateModelEntity entity = new CompanionPrivateModelEntity();
        apply(dto, entity);
        entity.setUserId(userId); entity.setCreator(userId); entity.setCreatedAt(new Date()); entity.setUpdatedAt(new Date());
        if (dao.insert(entity) != 1) throw new RenException("私有模型创建失败");
        audit.record(userId, userId, "private-model.create", "private-model", entity.getId(),
                Map.of("modelType", entity.getModelType(), "providerCode", entity.getProviderCode()));
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionPrivateModelVO update(Long userId, String id, CompanionPrivateModelSaveDTO dto) {
        CompanionPrivateModelEntity entity = dao.selectOwnedForUpdate(userId, id);
        if (entity == null) throw new RenException("私有模型不存在");
        requireType(dto.getModelType());
        apply(dto, entity); entity.setUpdater(userId); entity.setUpdatedAt(new Date());
        if (dao.updateById(entity) != 1) throw new RenException("私有模型不存在");
        audit.record(userId, userId, "private-model.update", "private-model", id,
                Map.of("modelType", entity.getModelType(), "providerCode", entity.getProviderCode()));
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String id) {
        CompanionPrivateModelEntity entity = dao.selectOwnedForUpdate(userId, id);
        if (entity == null) throw new RenException("私有模型不存在");
        long usage = bindingDao.countResourceUsage("private", id);
        if (usage > 0) throw new RenException("模型正在被 " + usage + " 个角色使用");
        if (dao.deleteById(id) != 1) throw new RenException("私有模型不存在");
        audit.record(userId, userId, "private-model.delete", "private-model", id,
                Map.of("modelType", entity.getModelType()));
    }

    @Override
    public CompanionModelTestVO test(Long userId, String id, CompanionPrivateModelSaveDTO dto) {
        CompanionPrivateModelEntity saved = id == null ? null : requireOwned(userId, id);
        String modelType = StringUtils.defaultIfBlank(dto.getModelType(), saved == null ? null : saved.getModelType());
        String providerCode = StringUtils.defaultIfBlank(dto.getProviderCode(), saved == null ? null : saved.getProviderCode());
        requireType(modelType);
        PreparedConfiguration prepared = prepare(dto, saved, modelType, providerCode);
        Map<String, Object> runtime = new LinkedHashMap<>(prepared.publicConfig());
        String apiUrl = StringUtils.defaultIfBlank(dto.getApiUrl(), saved == null ? null : saved.getApiUrl());
        String modelId = StringUtils.defaultIfBlank(dto.getModelId(), saved == null ? null : saved.getModelId());
        if (StringUtils.isNotBlank(apiUrl)) runtime.put("base_url", apiUrl);
        if (StringUtils.isNotBlank(modelId)) runtime.put("model_name", modelId);
        runtime.putAll(prepared.secretConfig());
        return tester.test(providerCode, runtime);
    }

    @Override
    public CompanionPrivateModelEntity requireOwned(Long userId, String id) {
        CompanionPrivateModelEntity entity = dao.selectOwned(userId, id);
        if (entity == null) throw new RenException("私有模型不存在");
        return entity;
    }

    private void apply(CompanionPrivateModelSaveDTO dto, CompanionPrivateModelEntity entity) {
        PreparedConfiguration prepared = prepare(dto, entity.getId() == null ? null : entity,
                dto.getModelType(), dto.getProviderCode());
        entity.setModelType(dto.getModelType()); entity.setName(dto.getName().trim());
        entity.setProviderCode(dto.getProviderCode().trim());
        entity.setVendorName(dto.getVendorName().trim());
        entity.setProtocol(dto.getProtocol().trim());
        entity.setCredentialRequired(Boolean.FALSE.equals(dto.getCredentialRequired()) ? 0 : 1);
        entity.setProviderTemplateId(StringUtils.trimToNull(dto.getProviderTemplateId()));
        entity.setApiUrl(StringUtils.trimToNull(dto.getApiUrl()));
        entity.setModelId(StringUtils.trimToNull(dto.getModelId())); entity.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        entity.setConfigJson(prepared.publicConfig().isEmpty() ? null : new JSONObject(prepared.publicConfig()));
        entity.setSecretConfigCiphertext(secrets.encryptMap(prepared.secretConfig()));
        if (prepared.clearedSecretKeys().contains("api_key")) entity.setApiKeyCiphertext(null);
    }

    private CompanionPrivateModelVO toVO(CompanionPrivateModelEntity entity) {
        CompanionPrivateModelVO vo = new CompanionPrivateModelVO();
        vo.setId(entity.getId()); vo.setModelType(entity.getModelType()); vo.setName(entity.getName());
        vo.setProviderCode(entity.getProviderCode());
        vo.setVendorName(StringUtils.defaultIfBlank(entity.getVendorName(), entity.getProviderCode()));
        vo.setProtocol(entity.getProtocol());
        vo.setCredentialRequired(!Integer.valueOf(0).equals(entity.getCredentialRequired()));
        vo.setProviderTemplateId(entity.getProviderTemplateId()); vo.setSource("private");
        vo.setApiUrl(entity.getApiUrl()); vo.setModelId(entity.getModelId());
        vo.setConfig(entity.getConfigJson());
        Set<String> configuredSecretKeys = configuredSecretKeys(entity);
        vo.setConfiguredSecretKeys(configuredSecretKeys);
        vo.setApiKeyConfigured(configuredSecretKeys.contains("api_key"));
        vo.setEnabled(Integer.valueOf(1).equals(entity.getEnabled()));
        vo.setUsageCount(bindingDao.countResourceUsage("private", entity.getId()));
        return vo;
    }

    private PreparedConfiguration prepare(CompanionPrivateModelSaveDTO dto, CompanionPrivateModelEntity saved,
            String modelType, String providerCode) {
        CompanionModelProviderTemplateVO template = StringUtils.isBlank(dto.getProviderTemplateId()) ? null
                : templates.require(dto.getProviderTemplateId(), modelType, providerCode);
        Set<String> templateSecretKeys = template == null ? Set.of() : template.getFields().stream()
                .filter(CompanionModelProviderFieldVO::isSecret)
                .map(CompanionModelProviderFieldVO::getKey)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Object> publicConfig = dto.getConfig() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(dto.getConfig());
        Map<String, Object> submittedSecrets = dto.getSecrets() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(dto.getSecrets());
        for (String key : new ArrayList<>(publicConfig.keySet())) {
            if (templateSecretKeys.contains(key) || templates.isSecretKey(key)) {
                submittedSecrets.putIfAbsent(key, publicConfig.remove(key));
            }
        }

        Map<String, Object> secretConfig = savedSecrets(saved);
        submittedSecrets.forEach((key, value) -> {
            Object normalized = normalizeSecret(value);
            if (normalized != null) secretConfig.put(key, normalized);
        });
        Set<String> cleared = dto.getClearSecretKeys() == null
                ? Set.of() : Set.copyOf(dto.getClearSecretKeys());
        cleared.forEach(secretConfig::remove);
        if (StringUtils.isNotBlank(dto.getApiKey())) secretConfig.put("api_key", dto.getApiKey().trim());

        if (template != null) validateTemplate(template, dto, publicConfig, secretConfig);
        return new PreparedConfiguration(publicConfig, secretConfig, cleared);
    }

    private Map<String, Object> savedSecrets(CompanionPrivateModelEntity saved) {
        if (saved == null) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>(secrets.decryptMap(saved.getSecretConfigCiphertext()));
        if (!result.containsKey("api_key") && StringUtils.isNotBlank(saved.getApiKeyCiphertext())) {
            String legacyApiKey = secrets.decrypt(saved.getApiKeyCiphertext());
            if (StringUtils.isNotBlank(legacyApiKey)) result.put("api_key", legacyApiKey);
        }
        return result;
    }

    private Set<String> configuredSecretKeys(CompanionPrivateModelEntity entity) {
        Set<String> result = new java.util.LinkedHashSet<>(secrets.decryptMap(entity.getSecretConfigCiphertext()).keySet());
        if (StringUtils.isNotBlank(entity.getApiKeyCiphertext())) result.add("api_key");
        return Set.copyOf(result);
    }

    private Object normalizeSecret(Object value) {
        if (value instanceof String text) return StringUtils.trimToNull(text);
        return value;
    }

    private void validateTemplate(CompanionModelProviderTemplateVO template, CompanionPrivateModelSaveDTO dto,
            Map<String, Object> publicConfig, Map<String, Object> secretConfig) {
        for (CompanionModelProviderFieldVO field : template.getFields()) {
            Object value = field.isSecret() ? secretConfig.get(field.getKey())
                    : publicValue(field.getKey(), dto, publicConfig);
            if (field.isRequired() && isMissing(value)) {
                throw new RenException("模型参数 " + field.getKey() + " 不能为空");
            }
            if (!isMissing(value) && !matchesType(field.getType(), value)) {
                throw new RenException("模型参数 " + field.getKey() + " 类型无效");
            }
            if (!isMissing(value) && field.getOptions() != null && !field.getOptions().isEmpty()
                    && !field.getOptions().contains(value)) {
                throw new RenException("模型参数 " + field.getKey() + " 选项无效");
            }
        }
    }

    private Object publicValue(String key, CompanionPrivateModelSaveDTO dto, Map<String, Object> config) {
        if (Set.of("base_url", "api_url", "url").contains(key) && StringUtils.isNotBlank(dto.getApiUrl())) {
            return dto.getApiUrl();
        }
        if (Set.of("model", "model_name").contains(key) && StringUtils.isNotBlank(dto.getModelId())) {
            return dto.getModelId();
        }
        return config.get(key);
    }

    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "dict" -> value instanceof Map<?, ?> || value instanceof JSONObject;
            default -> false;
        };
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && StringUtils.isBlank(text);
    }

    private void requireType(String type) { if (!TYPES.contains(type)) throw new RenException("模型类型不支持"); }

    private record PreparedConfiguration(Map<String, Object> publicConfig, Map<String, Object> secretConfig,
            Set<String> clearedSecretKeys) {}
}
