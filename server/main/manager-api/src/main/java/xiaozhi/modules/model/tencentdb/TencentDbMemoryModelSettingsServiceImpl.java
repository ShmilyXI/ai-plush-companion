package xiaozhi.modules.model.tencentdb;

import java.net.URI;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

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
        URI llmBaseUrl = httpUri(config.getStr("llm_base_url"));
        String llmApiKey = required(config.getStr("llm_api_key"));
        String llmModel = required(config.getStr("llm_model"));
        URI embeddingBaseUrl = httpUri(config.getStr("embedding_base_url"));
        String embeddingApiKey = required(config.getStr("embedding_api_key"));
        String embeddingModel = required(config.getStr("embedding_model"));
        Integer dimensions = config.getInt("embedding_dimensions");
        if (dimensions == null || dimensions <= 0) {
            throw invalid();
        }
        boolean sendDimensions = config.getBool("embedding_send_dimensions", true);
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
