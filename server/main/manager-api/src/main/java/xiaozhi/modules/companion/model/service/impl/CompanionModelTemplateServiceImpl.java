package xiaozhi.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.model.service.CompanionModelTemplateService;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderFieldVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.service.ModelProviderService;

@Slf4j
@Service
@AllArgsConstructor
public class CompanionModelTemplateServiceImpl implements CompanionModelTemplateService {
    private static final List<String> MODEL_TYPES = List.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private final ModelProviderService providers;

    @Override
    public List<CompanionModelProviderTemplateVO> list(String modelType) {
        validateModelType(modelType);
        List<CompanionModelProviderTemplateVO> result = new ArrayList<>();
        for (ModelProviderDTO provider : providers.getListByModelType(modelType)) {
            try {
                result.add(parse(provider));
            } catch (RuntimeException exception) {
                log.warn("Skipping invalid companion model provider template: {}", provider.getId());
            }
        }
        result.sort(Comparator
                .comparing(CompanionModelProviderTemplateVO::getSort,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(CompanionModelProviderTemplateVO::getName,
                        Comparator.nullsLast(String::compareTo)));
        return result;
    }

    @Override
    public CompanionModelProviderTemplateVO require(String id, String modelType, String providerCode) {
        validateModelType(modelType);
        ModelProviderDTO provider = providers.getById(id);
        if (provider == null || !modelType.equals(provider.getModelType())
                || !providerCode.equals(provider.getProviderCode())) {
            throw new RenException("模型模板不存在或类型不匹配");
        }
        try {
            return parse(provider);
        } catch (RuntimeException exception) {
            throw new RenException("模型模板配置无效");
        }
    }

    @Override
    public boolean isSecretKey(String key) {
        if (StringUtils.isBlank(key)) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("key") || normalized.endsWith("_key") || normalized.contains("_key_")
                || normalized.equals("secret") || normalized.endsWith("_secret") || normalized.contains("_secret_")
                || normalized.equals("token") || normalized.endsWith("_token") || normalized.contains("_token_")
                || normalized.contains("password") || normalized.contains("authorization")
                || normalized.contains("cookie") || normalized.contains("credential");
    }

    private CompanionModelProviderTemplateVO parse(ModelProviderDTO provider) {
        if (provider == null || StringUtils.isAnyBlank(provider.getId(), provider.getModelType(),
                provider.getProviderCode(), provider.getName(), provider.getFields())) {
            throw new IllegalArgumentException("provider fields are incomplete");
        }
        JSONArray fields = JSONUtil.parseArray(provider.getFields());
        CompanionModelProviderTemplateVO result = new CompanionModelProviderTemplateVO();
        result.setId(provider.getId());
        result.setModelType(provider.getModelType());
        result.setProviderCode(provider.getProviderCode());
        result.setName(provider.getName());
        result.setSort(provider.getSort());
        List<CompanionModelProviderFieldVO> parsedFields = new ArrayList<>();
        for (Object value : fields) parsedFields.add(parseField(value));
        result.setFields(parsedFields);
        return result;
    }

    private CompanionModelProviderFieldVO parseField(Object value) {
        JSONObject field = value instanceof JSONObject object ? object : new JSONObject(value);
        String key = field.getStr("key");
        String label = field.getStr("label");
        String rawType = field.getStr("type");
        if (StringUtils.isAnyBlank(key, label, rawType)) throw new IllegalArgumentException("invalid provider field");
        CompanionModelProviderFieldVO result = new CompanionModelProviderFieldVO();
        result.setKey(key);
        result.setLabel(label);
        result.setType(normalizeType(rawType));
        result.setRequired(Boolean.TRUE.equals(field.getBool("required")));
        result.setSecret("password".equalsIgnoreCase(rawType) || isSecretKey(key));
        JSONArray options = field.getJSONArray("options");
        result.setOptions(options == null ? List.of() : new ArrayList<>(options));
        result.setDefaultValue(field.get("default"));
        return result;
    }

    private String normalizeType(String rawType) {
        return switch (rawType.toLowerCase(Locale.ROOT)) {
            case "string", "password" -> "string";
            case "int", "float", "number" -> "number";
            case "bool", "boolean" -> "boolean";
            case "dict" -> "dict";
            default -> throw new IllegalArgumentException("unsupported provider field type");
        };
    }

    private void validateModelType(String modelType) {
        if (!MODEL_TYPES.contains(modelType)) throw new RenException("模型类型不支持");
    }
}
