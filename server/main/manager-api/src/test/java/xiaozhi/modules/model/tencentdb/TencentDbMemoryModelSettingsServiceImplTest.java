package xiaozhi.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

class TencentDbMemoryModelSettingsServiceImplTest {
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final TencentDbMemoryModelSettingsServiceImpl service =
            new TencentDbMemoryModelSettingsServiceImpl(models);

    @Test
    void readsEveryRequestThroughTheExistingModelCache() {
        when(models.getModelByIdFromCache("Memory_tencentdb"))
                .thenReturn(model(config("first-model")))
                .thenReturn(model(config("second-model")));

        assertEquals("first-model", service.requireEnabled().llmModel());
        assertEquals("second-model", service.requireEnabled().llmModel());
        verify(models, times(2)).getModelByIdFromCache("Memory_tencentdb");
    }

    @Test
    void parsesConnectionRuntimeWithoutExposingCoreSettingsToTheProxyRecord() {
        TencentDbMemoryRuntimeSettings runtime = service.parseRuntime(config("memory-model"));

        assertEquals("http://memory-core:8420", runtime.memoryCoreUrl().toString());
        assertEquals("core-secret", runtime.memoryCoreApiKey());
        assertEquals("https://llm.example/v1", runtime.modelSettings().llmBaseUrl().toString());
        assertEquals("https://embedding.example/v1", runtime.modelSettings().embeddingBaseUrl().toString());
        assertEquals(1024, runtime.modelSettings().embeddingDimensions());
        assertEquals(true, runtime.modelSettings().embeddingSendDimensions());
    }

    @Test
    void rejectsDisabledWrongTypeAndInvalidUrls() {
        ModelConfigEntity disabled = model(config("memory-model"));
        disabled.setIsEnabled(0);
        when(models.getModelByIdFromCache("Memory_tencentdb"))
                .thenReturn(disabled)
                .thenReturn(model(config("memory-model").set("type", "mem0ai")));

        assertEquals("TencentDB 记忆模型未启用",
                assertThrows(IllegalStateException.class, service::requireEnabled).getMessage());
        assertEquals("TencentDB 记忆模型配置不完整",
                assertThrows(IllegalStateException.class, service::requireEnabled).getMessage());

        JSONObject invalid = config("memory-model").set("llm_base_url", "file:///tmp/model");
        assertEquals("TencentDB 记忆模型配置不完整",
                assertThrows(IllegalStateException.class, () -> service.parseRuntime(invalid)).getMessage());
    }

    @Test
    void rejectsMissingSecretsWithoutReturningTheirValues() {
        JSONObject config = config("memory-model").set("llm_api_key", "");
        when(models.getModelByIdFromCache("Memory_tencentdb")).thenReturn(model(config));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::requireEnabled);

        assertEquals("TencentDB 记忆模型配置不完整", error.getMessage());
    }

    @Test
    void rejectsNonPositiveEmbeddingDimensions() {
        JSONObject config = config("memory-model").set("embedding_dimensions", 0);

        assertEquals("TencentDB 记忆模型配置不完整",
                assertThrows(IllegalStateException.class, () -> service.parseRuntime(config)).getMessage());
    }

    private ModelConfigEntity model(JSONObject config) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId("Memory_tencentdb");
        entity.setModelType("Memory");
        entity.setIsEnabled(1);
        entity.setConfigJson(config);
        return entity;
    }

    private JSONObject config(String llmModel) {
        return new JSONObject()
                .set("type", "tencentdb")
                .set("memory_core_url", "http://memory-core:8420")
                .set("memory_core_api_key", "core-secret")
                .set("llm_base_url", "https://llm.example/v1")
                .set("llm_api_key", "llm-secret")
                .set("llm_model", llmModel)
                .set("embedding_base_url", "https://embedding.example/v1")
                .set("embedding_api_key", "embedding-secret")
                .set("embedding_model", "text-embedding-v3")
                .set("embedding_dimensions", 1024)
                .set("embedding_send_dimensions", true);
    }
}
