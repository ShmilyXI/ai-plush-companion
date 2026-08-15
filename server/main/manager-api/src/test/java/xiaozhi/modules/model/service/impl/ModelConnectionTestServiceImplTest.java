package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.companion.model.service.CompanionModelConnectionTester;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.model.tencentdb.OpenAiEmbeddingConnectionTester;
import xiaozhi.modules.model.tencentdb.TencentDbMemoryConnectionTester;
import xiaozhi.modules.model.tencentdb.TencentDbMemoryModelSettingsService;
import xiaozhi.modules.model.tencentdb.TencentDbMemoryRuntimeSettings;

class ModelConnectionTestServiceImplTest {
    private ModelConfigDao modelConfigDao;
    private ModelProviderService modelProviderService;
    private CompanionModelConnectionTester tester;
    private OpenAiEmbeddingConnectionTester embeddingTester;
    private TencentDbMemoryConnectionTester memoryTester;
    private TencentDbMemoryModelSettingsService memorySettings;
    private ModelConnectionTestServiceImpl service;

    @BeforeEach
    void setUp() {
        modelConfigDao = mock(ModelConfigDao.class);
        modelProviderService = mock(ModelProviderService.class);
        tester = mock(CompanionModelConnectionTester.class);
        embeddingTester = mock(OpenAiEmbeddingConnectionTester.class);
        memoryTester = mock(TencentDbMemoryConnectionTester.class);
        memorySettings = mock(TencentDbMemoryModelSettingsService.class);
        service = new ModelConnectionTestServiceImpl(
                modelConfigDao, modelProviderService, tester, embeddingTester, memoryTester, memorySettings);
        when(modelProviderService.getList("LLM", "deepseek")).thenReturn(List.of(new ModelProviderDTO()));
        when(modelProviderService.getList("LLM", "openai")).thenReturn(List.of(new ModelProviderDTO()));
        when(modelProviderService.getList("TTS", "openai")).thenReturn(List.of(new ModelProviderDTO()));
        when(modelProviderService.getList("LLM", "gemini")).thenReturn(List.of(new ModelProviderDTO()));
        when(modelProviderService.getList("Memory", "tencentdb")).thenReturn(List.of(new ModelProviderDTO()));
        when(modelProviderService.getList("Embedding", "openai")).thenReturn(List.of(new ModelProviderDTO()));
        when(modelProviderService.getList("Embedding", "custom")).thenReturn(List.of(new ModelProviderDTO()));
    }

    @Test
    void savedModelKeepsOmittedCredentialAndUsesRuntimeProtocol() {
        ModelConfigEntity saved = savedModel();
        when(modelConfigDao.selectById("LLM_DeepSeek")).thenReturn(saved);
        when(tester.test(eq("openai"), anyMap()))
                .thenReturn(new CompanionModelTestVO(true, 12, "连接成功"));
        ModelConfigBodyDTO body = body(new JSONObject().set("base_url", "https://api.deepseek.com/v1"));

        CompanionModelTestVO result = service.test("LLM", "openai", "LLM_DeepSeek", body);

        ArgumentCaptor<Map<String, Object>> config = mapCaptor();
        verify(tester).test(eq("openai"), config.capture());
        assertEquals("saved-secret", config.getValue().get("api_key"));
        assertEquals("https://api.deepseek.com/v1", config.getValue().get("base_url"));
        assertEquals("连接成功", result.getMessage());
    }

    @Test
    void submittedCredentialReplacesSavedCredential() {
        when(modelConfigDao.selectById("LLM_DeepSeek")).thenReturn(savedModel());
        when(tester.test(eq("openai"), anyMap()))
                .thenReturn(new CompanionModelTestVO(true, 8, "连接成功"));
        ModelConfigBodyDTO body = body(new JSONObject().set("api_key", "new-secret"));

        service.test("LLM", "openai", "LLM_DeepSeek", body);

        ArgumentCaptor<Map<String, Object>> config = mapCaptor();
        verify(tester).test(eq("openai"), config.capture());
        assertEquals("new-secret", config.getValue().get("api_key"));
    }

    @Test
    void newModelUsesOnlySubmittedConfiguration() {
        ModelConfigBodyDTO body = body(new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://api.example/v1")
                .set("api_key", "new-secret"));
        when(tester.test(eq("openai"), anyMap()))
                .thenReturn(new CompanionModelTestVO(true, 8, "连接成功"));

        service.test("LLM", "openai", null, body);

        verify(modelConfigDao, never()).selectById(org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<Map<String, Object>> config = mapCaptor();
        verify(tester).test(eq("openai"), config.capture());
        assertEquals("new-secret", config.getValue().get("api_key"));
    }

    @Test
    void blankSubmittedCredentialDoesNotEraseSavedCredential() {
        when(modelConfigDao.selectById("LLM_DeepSeek")).thenReturn(savedModel());
        when(tester.test(eq("openai"), anyMap()))
                .thenReturn(new CompanionModelTestVO(true, 8, "连接成功"));
        ModelConfigBodyDTO body = body(new JSONObject().set("api_key", "   "));

        service.test("LLM", "openai", "LLM_DeepSeek", body);

        ArgumentCaptor<Map<String, Object>> config = mapCaptor();
        verify(tester).test(eq("openai"), config.capture());
        assertEquals("saved-secret", config.getValue().get("api_key"));
    }

    @Test
    void missingSavedModelFailsBeforeProbe() {
        when(modelConfigDao.selectById("missing")).thenReturn(null);
        ModelConfigBodyDTO body = body(new JSONObject().set("type", "openai"));

        try (MockedStatic<MessageUtils> messages = org.mockito.Mockito.mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.RESOURCE_NOT_FOUND)).thenReturn("资源不存在");
            assertThrows(RenException.class, () -> service.test("LLM", "openai", "missing", body));
        }

        verify(tester, never()).test(org.mockito.ArgumentMatchers.anyString(), anyMap());
    }

    @Test
    void ttsDoesNotUseTheGenericModelsProbe() {
        CompanionModelTestVO result = service.test("TTS", "openai", null,
                body(new JSONObject().set("api_url", "https://api.example/v1/audio/speech")));

        assertFalse(result.isSuccess());
        assertEquals("当前模型不支持自动测试", result.getMessage());
        verify(tester, never()).test(org.mockito.ArgumentMatchers.anyString(), anyMap());
    }

    @Test
    void nonOpenAiLlmDoesNotUseTheGenericModelsProbe() {
        CompanionModelTestVO result = service.test("LLM", "gemini", null,
                body(new JSONObject().set("api_key", "secret")));

        assertFalse(result.isSuccess());
        assertEquals("当前供应器不支持自动测试", result.getMessage());
        verify(tester, never()).test(org.mockito.ArgumentMatchers.anyString(), anyMap());
    }

    @Test
    void submittedRuntimeTypeCannotMakeUnsupportedProviderTestable() {
        CompanionModelTestVO result = service.test("LLM", "gemini", null,
                body(new JSONObject()
                        .set("type", "openai")
                        .set("api_key", "secret")));

        assertFalse(result.isSuccess());
        assertEquals("当前供应器不支持自动测试", result.getMessage());
        verify(tester, never()).test(org.mockito.ArgumentMatchers.anyString(), anyMap());
    }

    @Test
    void routesUnsavedTencentDbMemoryFormThroughSharedRuntimeParser() {
        JSONObject config = new JSONObject()
                .set("type", "tencentdb")
                .set("memory_core_url", "http://memory-core:8420")
                .set("memory_core_api_key", "core-secret");
        TencentDbMemoryRuntimeSettings runtime = mock(TencentDbMemoryRuntimeSettings.class);
        when(memorySettings.parseRuntime(config)).thenReturn(runtime);
        when(memoryTester.test(runtime)).thenReturn(new CompanionModelTestVO(true, 15, "全部可用"));

        CompanionModelTestVO result = service.test("Memory", "tencentdb", null, body(config));

        verify(memorySettings).parseRuntime(config);
        verify(memoryTester).test(runtime);
        assertEquals("全部可用", result.getMessage());
    }

    @Test
    void savedTencentDbMemoryFormPreservesBlankSecrets() {
        ModelConfigEntity saved = new ModelConfigEntity();
        saved.setId("Memory_tencentdb");
        saved.setModelType("Memory");
        JSONObject savedConfig = new JSONObject()
                .set("type", "tencentdb")
                .set("memory_core_api_key", "saved-core-secret")
                .set("llm_api_key", "saved-llm-secret");
        saved.setConfigJson(savedConfig);
        when(modelConfigDao.selectById("Memory_tencentdb")).thenReturn(saved);
        TencentDbMemoryRuntimeSettings runtime = mock(TencentDbMemoryRuntimeSettings.class);
        when(memorySettings.parseRuntime(org.mockito.ArgumentMatchers.any())).thenReturn(runtime);
        when(memoryTester.test(runtime)).thenReturn(new CompanionModelTestVO(true, 9, "全部可用"));

        service.test("Memory", "tencentdb", "Memory_tencentdb",
                body(new JSONObject().set("memory_core_api_key", " ").set("llm_api_key", "")));

        ArgumentCaptor<JSONObject> merged = ArgumentCaptor.forClass(JSONObject.class);
        verify(memorySettings).parseRuntime(merged.capture());
        assertEquals("saved-core-secret", merged.getValue().getStr("memory_core_api_key"));
        assertEquals("saved-llm-secret", merged.getValue().getStr("llm_api_key"));
    }

    @Test
    void savedEmbeddingKeepsOmittedCredential() {
        ModelConfigEntity saved = new ModelConfigEntity();
        saved.setId("Embedding_openai");
        saved.setModelType("Embedding");
        saved.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://embedding.example/v1")
                .set("api_key", "saved-embedding-secret")
                .set("model_name", "embedding-3")
                .set("dimensions", 1024));
        when(modelConfigDao.selectById("Embedding_openai")).thenReturn(saved);
        when(embeddingTester.test(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CompanionModelTestVO(true, 7, "Embedding 连接成功"));

        CompanionModelTestVO result = service.test("Embedding", "openai", "Embedding_openai",
                body(new JSONObject().set("api_key", " ").set("send_dimensions", true)));

        ArgumentCaptor<JSONObject> merged = ArgumentCaptor.forClass(JSONObject.class);
        verify(embeddingTester).test(merged.capture());
        assertEquals("saved-embedding-secret", merged.getValue().getStr("api_key"));
        assertEquals("Embedding 连接成功", result.getMessage());
    }

    @Test
    void unsupportedEmbeddingProviderDoesNotProbe() {
        CompanionModelTestVO result = service.test("Embedding", "custom", null,
                body(new JSONObject().set("api_key", "secret")));

        assertFalse(result.isSuccess());
        assertEquals("当前供应器不支持自动测试", result.getMessage());
        verify(embeddingTester, never()).test(org.mockito.ArgumentMatchers.any());
    }

    private ModelConfigEntity savedModel() {
        ModelConfigEntity saved = new ModelConfigEntity();
        saved.setId("LLM_DeepSeek");
        saved.setModelType("LLM");
        saved.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://api.deepseek.com")
                .set("api_key", "saved-secret"));
        return saved;
    }

    private ModelConfigBodyDTO body(JSONObject config) {
        ModelConfigBodyDTO body = new ModelConfigBodyDTO();
        body.setConfigJson(config);
        return body;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
