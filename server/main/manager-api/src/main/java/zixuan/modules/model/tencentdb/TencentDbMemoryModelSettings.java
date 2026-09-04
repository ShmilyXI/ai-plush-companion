package zixuan.modules.model.tencentdb;

import java.net.URI;

public record TencentDbMemoryModelSettings(
        URI llmBaseUrl,
        String llmApiKey,
        String llmModel,
        URI embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDimensions,
        boolean embeddingSendDimensions) {
}
