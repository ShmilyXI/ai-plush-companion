package zixuan.modules.model.tencentdb;

import java.net.URI;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;

@Service
@RequiredArgsConstructor
public class TencentDbMemoryModelSettingsServiceImpl implements TencentDbMemoryModelSettingsService {
    private static final String MODEL_ID = "Memory_tencentdb";
    private static final String INVALID = "TencentDB 记忆模型配置不完整";

    private final ModelConfigService modelConfigService;

    @Override
    public TencentDbMemoryModelSettings requireEnabled() {
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(MODEL_ID);
        if (model == null || !"Memory".equalsIgnoreCase(model.getModelType()) || model.getIsEnabled() == null
                || model.getIsEnabled() != 1) {
            throw new IllegalStateException("TencentDB 记忆模型未启用");
        }
        JSONObject config = model.getConfigJson();
        if (config == null || !"tencentdb".equalsIgnoreCase(config.getStr("type"))) {
            throw invalid();
        }
        return parseModelSettings(config);
    }

    @Override
    public TencentDbMemoryModelSettings parseModelSettings(JSONObject config) {
        if (config == null || !"tencentdb".equalsIgnoreCase(config.getStr("type"))) {
            throw invalid();
        }

        String llmModelId = StringUtils.trimToNull(config.getStr("llm_model_id"));
        String embeddingModelId = StringUtils.trimToNull(config.getStr("embedding_model_id"));
        if ((llmModelId == null) != (embeddingModelId == null)) {
            throw new IllegalStateException("TencentDB 记忆模型引用不完整");
        }
        if (llmModelId == null) {
            return settings(config, "llm_", "embedding_");
        }

        JSONObject llm = referenced(config, "llm_model_id", "LLM").getConfigJson();
        JSONObject embedding = referenced(config, "embedding_model_id", "Embedding").getConfigJson();
        return settings(llm, "", embedding, "");
    }

    private TencentDbMemoryModelSettings settings(JSONObject config, String llmPrefix, String embeddingPrefix) {
        return settings(config, llmPrefix, config, embeddingPrefix);
    }

    private TencentDbMemoryModelSettings settings(
            JSONObject llmConfig,
            String llmPrefix,
            JSONObject embeddingConfig,
            String embeddingPrefix) {
        URI llmBaseUrl = httpUri(llmConfig.getStr(llmPrefix + "base_url"));
        String llmApiKey = required(llmConfig.getStr(llmPrefix + "api_key"));
        String llmModel = required(llmConfig.getStr(modelKey(llmPrefix)));
        URI embeddingBaseUrl = httpUri(embeddingConfig.getStr(embeddingPrefix + "base_url"));
        String embeddingApiKey = required(embeddingConfig.getStr(embeddingPrefix + "api_key"));
        String embeddingModel = required(embeddingConfig.getStr(modelKey(embeddingPrefix)));
        Integer dimensions = embeddingConfig.getInt(embeddingPrefix + "dimensions");
        if (dimensions == null || dimensions <= 0) {
            throw invalid();
        }
        boolean sendDimensions = embeddingConfig.getBool(embeddingPrefix + "send_dimensions", true);
        return new TencentDbMemoryModelSettings(
                llmBaseUrl,
                llmApiKey,
                llmModel,
                embeddingBaseUrl,
                embeddingApiKey,
                embeddingModel,
                dimensions,
                sendDimensions);
    }

    private String modelKey(String prefix) {
        return prefix.isEmpty() ? "model_name" : prefix + "model";
    }

    private ModelConfigEntity referenced(JSONObject memory, String key, String modelType) {
        String id = StringUtils.trimToNull(memory.getStr(key));
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(id);
        if (model == null || !modelType.equalsIgnoreCase(model.getModelType())
                || model.getIsEnabled() == null || model.getIsEnabled() != 1) {
            throw new IllegalStateException("TencentDB 记忆引用的 " + modelType + " 模型不可用");
        }
        JSONObject config = model.getConfigJson();
        if (config == null || !"openai".equalsIgnoreCase(config.getStr("type"))) {
            throw new IllegalStateException("TencentDB 记忆仅支持 OpenAI 兼容的 " + modelType + " 模型");
        }
        return model;
    }

    @Override
    public TencentDbMemoryRuntimeSettings parseRuntime(JSONObject config) {
        URI memoryCoreUrl = httpUri(config == null ? null : config.getStr("memory_core_url"));
        String memoryCoreApiKey = required(config == null ? null : config.getStr("memory_core_api_key"));
        return new TencentDbMemoryRuntimeSettings(memoryCoreUrl, memoryCoreApiKey, parseModelSettings(config));
    }

    private URI httpUri(String value) {
        try {
            URI uri = URI.create(required(value));
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private String required(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid();
        }
        return value.trim();
    }

    private IllegalStateException invalid() {
        return new IllegalStateException(INVALID);
    }
}
