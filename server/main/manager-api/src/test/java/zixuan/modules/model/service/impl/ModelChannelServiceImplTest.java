package zixuan.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import cn.hutool.json.JSONObject;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.model.dao.ModelChannelDao;
import zixuan.modules.model.dao.ModelConfigDao;
import zixuan.modules.model.dto.ModelChannelAddModelsDTO;
import zixuan.modules.model.dto.ModelChannelDTO;
import zixuan.modules.model.dto.ModelChannelModelDTO;
import zixuan.modules.model.dto.ModelChannelModelUpdateDTO;
import zixuan.modules.model.dto.ModelChannelSaveDTO;
import zixuan.modules.model.dto.ModelChannelUpstreamModelDTO;
import zixuan.modules.model.dto.ModelProviderDTO;
import zixuan.modules.model.entity.ModelChannelEntity;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelChannelUpstreamClient;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.service.ModelProviderService;

class ModelChannelServiceImplTest {
    private static final String CIPHER_PREFIX = "cipher:";
    private static final String LLM_FIELDS = """
            [{"key":"base_url","type":"string","label":"地址"},
             {"key":"model_name","type":"string","label":"模型"},
             {"key":"api_key","type":"password","label":"密钥"},
             {"key":"temperature","type":"number","label":"温度","default":1.3},
             {"key":"max_tokens","type":"number","label":"最大输出长度","default":2048},
             {"key":"top_k","type":"number","label":"候选词数量"}]
            """;

    private ModelChannelDao channelDao;
    private ModelConfigDao modelConfigDao;
    private ModelConfigService modelConfigService;
    private ModelProviderService modelProviderService;
    private ModelChannelUpstreamClient upstream;
    private ModelChannelServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        channelDao = mock(ModelChannelDao.class);
        modelConfigDao = mock(ModelConfigDao.class);
        modelConfigService = mock(ModelConfigService.class);
        modelProviderService = mock(ModelProviderService.class);
        upstream = mock(ModelChannelUpstreamClient.class);
        CompanionModelSecretService secrets = mock(CompanionModelSecretService.class);
        when(secrets.encryptMap(any())).thenAnswer(invocation ->
                CIPHER_PREFIX + new JSONObject((Map<String, Object>) invocation.getArgument(0)));
        when(secrets.decryptMap(any())).thenAnswer(invocation -> {
            String ciphertext = invocation.getArgument(0);
            if (ciphertext == null || !ciphertext.startsWith(CIPHER_PREFIX)) return Map.of();
            return new LinkedHashMap<>(new JSONObject(ciphertext.substring(CIPHER_PREFIX.length())));
        });
        service = new ModelChannelServiceImpl(
                channelDao, modelConfigDao, modelConfigService, modelProviderService, secrets, upstream);

        when(channelDao.insert(any(ModelChannelEntity.class))).thenReturn(1);
        when(channelDao.updateById(any(ModelChannelEntity.class))).thenReturn(1);
        when(channelDao.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(modelConfigDao.insert(any(ModelConfigEntity.class))).thenReturn(1);
        when(modelConfigDao.updateById(any(ModelConfigEntity.class))).thenReturn(1);
        when(modelConfigDao.selectList(any(Wrapper.class))).thenReturn(List.of());

        ModelProviderDTO llmProvider = new ModelProviderDTO();
        llmProvider.setFields(LLM_FIELDS);
        when(modelProviderService.getList("LLM", "openai")).thenReturn(List.of(llmProvider));
        when(modelProviderService.getList("VLLM", "ollama")).thenReturn(List.of());
    }

    @Test
    void createStoresTheKeyEncryptedAndNeverReturnsIt() {
        ModelChannelSaveDTO dto = saveDto("DeepSeek", "openai", "https://api.deepseek.com/", "sk-test-secret");

        ModelChannelDTO result = service.create(dto);

        ArgumentCaptor<ModelChannelEntity> saved = ArgumentCaptor.forClass(ModelChannelEntity.class);
        verify(channelDao).insert(saved.capture());
        assertEquals("https://api.deepseek.com", saved.getValue().getBaseUrl());
        assertTrue(saved.getValue().getSecretConfigCiphertext().startsWith(CIPHER_PREFIX));
        assertTrue(result.isCredentialConfigured());
        assertFalse(result.toString().contains("sk-test-secret"));
        assertEquals(0, result.getModelCount());
    }

    @Test
    void createRejectsAddressesThatAreNotPlainHttpUrls() {
        for (String url : List.of("ftp://example.com", "api.deepseek.com", "https://user:pw@example.com",
                "https://example.com/v1?key=1", "")) {
            assertThrows(RenException.class,
                    () -> service.create(saveDto("坏地址", "openai", url, null)), url);
        }
        assertThrows(RenException.class,
                () -> service.create(saveDto("坏协议", "gemini", "https://example.com", null)));
        verify(channelDao, never()).insert(any(ModelChannelEntity.class));
    }

    @Test
    void updateKeepsTheSavedKeyWhenLeftBlankAndSyncsLinkedModels() {
        ModelChannelEntity channel = channel("c1", "openai", "https://old.example/v1", "sk-old");
        when(channelDao.selectById("c1")).thenReturn(channel);
        ModelConfigEntity linked = linkedModel("m1", "c1", "LLM", new JSONObject()
                .set("type", "openai").set("base_url", "https://old.example/v1")
                .set("api_key", "sk-old").set("model_name", "deepseek-chat").set("temperature", 0.7));
        when(modelConfigDao.selectList(any(Wrapper.class))).thenReturn(List.of(linked));

        ModelChannelDTO result = service.update("c1", saveDto("DeepSeek", null, "https://new.example/v1", " "));

        ArgumentCaptor<ModelConfigEntity> patch = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigDao).updateById(patch.capture());
        JSONObject config = patch.getValue().getConfigJson();
        assertEquals("m1", patch.getValue().getId());
        assertEquals("https://new.example/v1", config.getStr("base_url"));
        assertEquals("sk-old", config.getStr("api_key"));
        assertEquals("deepseek-chat", config.getStr("model_name"));
        assertEquals(0.7, config.getDouble("temperature"));
        assertNull(patch.getValue().getModelName());
        verify(modelConfigService).evictModelCache("m1");
        assertEquals(1, result.getModelCount());
        assertTrue(result.isCredentialConfigured());
    }

    @Test
    void clearingTheKeyLeavesLinkedOpenAiModelsWithThePlaceholder() {
        ModelChannelEntity channel = channel("c1", "openai", "http://localhost:1234/v1", "sk-old");
        when(channelDao.selectById("c1")).thenReturn(channel);
        ModelConfigEntity linked = linkedModel("m1", "c1", "LLM", new JSONObject()
                .set("type", "openai").set("api_key", "sk-old").set("model_name", "local"));
        when(modelConfigDao.selectList(any(Wrapper.class))).thenReturn(List.of(linked));
        ModelChannelSaveDTO dto = saveDto("本地", null, "http://localhost:1234/v1", null);
        dto.setClearApiKey(true);

        ModelChannelDTO result = service.update("c1", dto);

        ArgumentCaptor<ModelConfigEntity> patch = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigDao).updateById(patch.capture());
        assertEquals("EMPTY", patch.getValue().getConfigJson().getStr("api_key"));
        assertFalse(result.isCredentialConfigured());
        assertNull(channel.getSecretConfigCiphertext());
    }

    @Test
    void updateCannotChangeTheProtocol() {
        when(channelDao.selectById("c1")).thenReturn(channel("c1", "openai", "https://a.example", null));

        assertThrows(RenException.class,
                () -> service.update("c1", saveDto("名字", "ollama", "https://a.example", null)));
        verify(channelDao, never()).updateById(any(ModelChannelEntity.class));
    }

    @Test
    void deleteIsRefusedWhileModelsAreStillLinked() {
        when(channelDao.selectById("c1")).thenReturn(channel("c1", "openai", "https://a.example", null));
        when(modelConfigDao.selectList(any(Wrapper.class)))
                .thenReturn(List.of(linkedModel("m1", "c1", "LLM", new JSONObject())));

        assertThrows(RenException.class, () -> service.delete("c1"));
        verify(channelDao, never()).deleteById(anyString());
    }

    @Test
    void addModelsBuildsRuntimeConfigFromTheChannelAndProviderDefaults() {
        when(channelDao.selectById("c1"))
                .thenReturn(channel("c1", "openai", "https://api.deepseek.com", "sk-test-secret"));
        ModelConfigEntity existing = linkedModel("m0", "c1", "LLM",
                new JSONObject().set("model_name", "deepseek-chat"));
        when(modelConfigDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        List<ModelChannelModelDTO> created = service.addModels("c1", addDto(
                item("LLM", "deepseek-chat", null),
                item("llm", " deepseek-reasoner ", null)));

        ArgumentCaptor<ModelConfigEntity> saved = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigDao, times(1)).insert(saved.capture());
        ModelConfigEntity model = saved.getValue();
        assertEquals("LLM", model.getModelType());
        assertEquals("c1", model.getChannelId());
        assertEquals("DeepSeek / deepseek-reasoner", model.getModelName());
        assertEquals(1, model.getIsEnabled());
        assertEquals(0, model.getIsDefault());
        JSONObject config = model.getConfigJson();
        assertEquals("openai", config.getStr("type"));
        assertEquals("https://api.deepseek.com", config.getStr("base_url"));
        assertEquals("sk-test-secret", config.getStr("api_key"));
        assertEquals("deepseek-reasoner", config.getStr("model_name"));
        assertEquals(1.3, config.getDouble("temperature"));
        assertEquals(2048, config.getInt("max_tokens"));
        assertFalse(config.containsKey("top_k"));

        assertEquals(1, created.size());
        assertEquals("deepseek-reasoner", created.get(0).getModelId());
        assertEquals(Map.of("temperature", 1.3, "max_tokens", 2048.0),
                normalizeNumbers(created.get(0).getParams()));
        assertFalse(created.toString().contains("sk-test-secret"));
    }

    @Test
    void addModelsRejectsTypesTheProtocolCannotServe() {
        when(channelDao.selectById("c2")).thenReturn(channel("c2", "ollama", "http://localhost:11434", null));

        assertThrows(RenException.class, () -> service.addModels("c2", addDto(item("VLLM", "llava", null))));
        assertThrows(RenException.class, () -> service.addModels("c2", addDto(item("TTS", "voice", null))));
        assertThrows(RenException.class, () -> service.addModels("c2", addDto()));
        verify(modelConfigDao, never()).insert(any(ModelConfigEntity.class));
    }

    @Test
    void updateModelChangesOnlyDeclaredParameters() {
        ModelConfigEntity model = linkedModel("m1", "c1", "LLM", new JSONObject()
                .set("type", "openai").set("base_url", "https://api.deepseek.com")
                .set("api_key", "sk-test-secret").set("model_name", "deepseek-chat")
                .set("temperature", 1.3).set("max_tokens", 2048));
        when(modelConfigDao.selectById("m1")).thenReturn(model);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("temperature", 0.6);
        params.put("max_tokens", null);
        ModelChannelModelUpdateDTO dto = new ModelChannelModelUpdateDTO();
        dto.setModelName("陪伴对话");
        dto.setParams(params);

        ModelChannelModelDTO result = service.updateModel("m1", dto);

        ArgumentCaptor<ModelConfigEntity> patch = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigDao).updateById(patch.capture());
        JSONObject config = patch.getValue().getConfigJson();
        assertEquals("陪伴对话", patch.getValue().getModelName());
        assertEquals(0.6, config.getDouble("temperature"));
        assertFalse(config.containsKey("max_tokens"));
        assertEquals("sk-test-secret", config.getStr("api_key"));
        assertEquals("https://api.deepseek.com", config.getStr("base_url"));
        assertEquals("陪伴对话", result.getModelName());
        assertFalse(result.toString().contains("sk-test-secret"));
        verify(modelConfigService).evictModelCache("m1");
    }

    @Test
    void updateModelRefusesConnectionOwnedKeysAndUnlinkedModels() {
        ModelConfigEntity model = linkedModel("m1", "c1", "LLM",
                new JSONObject().set("type", "openai").set("model_name", "deepseek-chat"));
        when(modelConfigDao.selectById("m1")).thenReturn(model);
        when(modelConfigDao.selectById("legacy")).thenReturn(linkedModel("legacy", null, "LLM", new JSONObject()));

        for (String key : List.of("base_url", "api_key", "type", "model_name", "unknown")) {
            ModelChannelModelUpdateDTO dto = new ModelChannelModelUpdateDTO();
            dto.setModelName("名称");
            dto.setParams(Map.of(key, "x"));
            assertThrows(RenException.class, () -> service.updateModel("m1", dto), key);
        }
        ModelChannelModelUpdateDTO dto = new ModelChannelModelUpdateDTO();
        dto.setModelName("名称");
        assertThrows(RenException.class, () -> service.updateModel("legacy", dto));
        verify(modelConfigDao, never()).updateById(any(ModelConfigEntity.class));
    }

    @Test
    void testReportsUpstreamFailureWithoutLeakingTheKey() {
        when(channelDao.selectById("c1"))
                .thenReturn(channel("c1", "openai", "https://api.deepseek.com", "sk-test-secret"));
        when(upstream.listModels("openai", "https://api.deepseek.com", "sk-test-secret"))
                .thenThrow(new RenException("密钥无效或没有权限（HTTP 401）"));

        CompanionModelTestVO failed = service.test("c1", null);

        assertFalse(failed.isSuccess());
        assertEquals("密钥无效或没有权限（HTTP 401）", failed.getMessage());
        assertFalse(failed.toString().contains("sk-test-secret"));

        when(upstream.listModels("openai", "https://api.example/v1", "sk-new"))
                .thenReturn(List.of("a", "b"));
        CompanionModelTestVO unsaved = service.test(null,
                saveDto(null, "openai", "https://api.example/v1/", "sk-new"));
        assertTrue(unsaved.isSuccess());
        assertEquals("连接成功，发现 2 个模型", unsaved.getMessage());
    }

    @Test
    void upstreamModelsMarksWhatIsAlreadyAdded() {
        when(channelDao.selectById("c1"))
                .thenReturn(channel("c1", "openai", "https://api.deepseek.com", "sk-test-secret"));
        when(upstream.listModels("openai", "https://api.deepseek.com", "sk-test-secret"))
                .thenReturn(List.of("deepseek-chat", "deepseek-reasoner"));
        when(modelConfigDao.selectList(any(Wrapper.class))).thenReturn(List.of(
                linkedModel("m1", "c1", "LLM", new JSONObject().set("model_name", "deepseek-chat"))));

        List<ModelChannelUpstreamModelDTO> result = service.upstreamModels("c1");

        assertEquals(List.of(
                new ModelChannelUpstreamModelDTO("deepseek-chat", List.of("LLM")),
                new ModelChannelUpstreamModelDTO("deepseek-reasoner", List.of())), result);
    }

    private Map<String, Double> normalizeNumbers(Map<String, Object> values) {
        Map<String, Double> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, ((Number) value).doubleValue()));
        return result;
    }

    private ModelChannelSaveDTO saveDto(String name, String providerCode, String baseUrl, String apiKey) {
        ModelChannelSaveDTO dto = new ModelChannelSaveDTO();
        dto.setName(name);
        dto.setProviderCode(providerCode);
        dto.setBaseUrl(baseUrl);
        dto.setApiKey(apiKey);
        return dto;
    }

    private ModelChannelEntity channel(String id, String providerCode, String baseUrl, String apiKey) {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(id);
        channel.setName("DeepSeek");
        channel.setProviderCode(providerCode);
        channel.setBaseUrl(baseUrl);
        channel.setSort(0);
        if (apiKey != null) {
            channel.setSecretConfigCiphertext(CIPHER_PREFIX + new JSONObject().set("api_key", apiKey));
        }
        return channel;
    }

    private ModelConfigEntity linkedModel(String id, String channelId, String modelType, JSONObject config) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setChannelId(channelId);
        model.setModelType(modelType);
        model.setModelName("模型 " + id);
        model.setIsEnabled(1);
        model.setIsDefault(0);
        model.setConfigJson(config);
        return model;
    }

    private ModelChannelAddModelsDTO addDto(ModelChannelAddModelsDTO.Item... items) {
        ModelChannelAddModelsDTO dto = new ModelChannelAddModelsDTO();
        dto.setModels(List.of(items));
        return dto;
    }

    private ModelChannelAddModelsDTO.Item item(String modelType, String modelId, String modelName) {
        ModelChannelAddModelsDTO.Item item = new ModelChannelAddModelsDTO.Item();
        item.setModelType(modelType);
        item.setModelId(modelId);
        item.setModelName(modelName);
        return item;
    }
}
