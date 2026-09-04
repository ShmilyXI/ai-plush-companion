package zixuan.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;

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

    @Test
    void resolvesManagedModelsBeforeLegacyFields() {
        when(models.getModelByIdFromCache("LLM_GLM")).thenReturn(referencedModel(
                "LLM_GLM", "LLM", 1,
                new JSONObject()
                        .set("type", "openai")
                        .set("base_url", "https://open.bigmodel.cn/api/paas/v4")
                        .set("api_key", "glm-key")
                        .set("model_name", "glm-4-flash")));
        when(models.getModelByIdFromCache("Embedding_zhipu")).thenReturn(referencedModel(
                "Embedding_zhipu", "Embedding", 1,
                new JSONObject()
                        .set("type", "openai")
                        .set("base_url", "https://open.bigmodel.cn/api/paas/v4")
                        .set("api_key", "embedding-key")
                        .set("model_name", "embedding-3")
                        .set("dimensions", 1024)
                        .set("send_dimensions", true)));

        TencentDbMemoryModelSettings settings = service.parseModelSettings(new JSONObject()
                .set("type", "tencentdb")
                .set("llm_model_id", "LLM_GLM")
                .set("embedding_model_id", "Embedding_zhipu")
                .set("llm_base_url", "https://legacy.invalid")
                .set("llm_api_key", "legacy-key")
                .set("llm_model", "legacy-model"));

        assertEquals("glm-4-flash", settings.llmModel());
        assertEquals("embedding-3", settings.embeddingModel());
        assertEquals(1024, settings.embeddingDimensions());
        assertEquals(true, settings.embeddingSendDimensions());
    }

    @Test
    void rejectsMissingDisabledOrIncompatibleReferences() {
        when(models.getModelByIdFromCache("LLM_disabled")).thenReturn(referencedModel(
                "LLM_disabled", "LLM", 0, new JSONObject().set("type", "openai")));
        when(models.getModelByIdFromCache("LLM_gemini")).thenReturn(referencedModel(
                "LLM_gemini", "LLM", 1, new JSONObject().set("type", "gemini")));

        JSONObject disabled = new JSONObject()
                .set("type", "tencentdb")
                .set("llm_model_id", "LLM_disabled")
                .set("embedding_model_id", "Embedding_zhipu");
        assertEquals("TencentDB 记忆引用的 LLM 模型不可用",
                assertThrows(IllegalStateException.class, () -> service.parseModelSettings(disabled)).getMessage());

        JSONObject incompatible = new JSONObject()
                .set("type", "tencentdb")
                .set("llm_model_id", "LLM_gemini")
                .set("embedding_model_id", "Embedding_zhipu");
        assertEquals("TencentDB 记忆仅支持 OpenAI 兼容的 LLM 模型",
                assertThrows(IllegalStateException.class, () -> service.parseModelSettings(incompatible)).getMessage());
    }

    @Test
    void rejectsUnavailableAndIncompleteEmbeddingReferences() {
        when(models.getModelByIdFromCache("LLM_GLM")).thenReturn(referencedModel(
                "LLM_GLM", "LLM", 1,
                new JSONObject()
                        .set("type", "openai")
                        .set("base_url", "https://open.bigmodel.cn/api/paas/v4")
                        .set("api_key", "glm-key")
                        .set("model_name", "glm-4-flash")));
        when(models.getModelByIdFromCache("Embedding_missing")).thenReturn(null);
        when(models.getModelByIdFromCache("Embedding_incomplete")).thenReturn(referencedModel(
                "Embedding_incomplete", "Embedding", 1,
                new JSONObject()
                        .set("type", "openai")
                        .set("base_url", "https://open.bigmodel.cn/api/paas/v4")
                        .set("api_key", "embedding-key")
                        .set("model_name", "embedding-3")
                        .set("dimensions", 0)));

        JSONObject missing = new JSONObject()
                .set("type", "tencentdb")
                .set("llm_model_id", "LLM_GLM")
                .set("embedding_model_id", "Embedding_missing");
        assertEquals("TencentDB 记忆引用的 Embedding 模型不可用",
                assertThrows(IllegalStateException.class, () -> service.parseModelSettings(missing)).getMessage());

        JSONObject incomplete = new JSONObject()
                .set("type", "tencentdb")
                .set("llm_model_id", "LLM_GLM")
                .set("embedding_model_id", "Embedding_incomplete");
        assertEquals("TencentDB 记忆模型配置不完整",
                assertThrows(IllegalStateException.class, () -> service.parseModelSettings(incomplete)).getMessage());
    }

    @Test
    void rejectsHalfConfiguredReferencePair() {
        JSONObject partial = config("legacy-model")
                .set("llm_model_id", "LLM_GLM")
                .set("embedding_model_id", "");

        assertEquals("TencentDB 记忆模型引用不完整",
                assertThrows(IllegalStateException.class, () -> service.parseModelSettings(partial)).getMessage());
    }

    private ModelConfigEntity model(JSONObject config) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId("Memory_tencentdb");
        entity.setModelType("Memory");
        entity.setIsEnabled(1);
        entity.setConfigJson(config);
        return entity;
    }

    private ModelConfigEntity referencedModel(String id, String type, int enabled, JSONObject config) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(id);
        entity.setModelType(type);
        entity.setIsEnabled(enabled);
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
