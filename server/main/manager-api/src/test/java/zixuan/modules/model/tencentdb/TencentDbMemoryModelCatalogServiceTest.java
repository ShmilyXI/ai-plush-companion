package zixuan.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import zixuan.modules.model.dao.ModelConfigDao;
import zixuan.modules.model.dto.ModelProviderDTO;
import zixuan.modules.model.entity.ModelConfigEntity;

class TencentDbMemoryModelCatalogServiceTest {
    private final ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
    private final TencentDbMemoryModelCatalogService service = new TencentDbMemoryModelCatalogService(modelConfigDao);

    @Test
    void enrichesTencentDbFieldsWithoutCredentials() {
        when(modelConfigDao.selectList(any()))
                .thenReturn(List.of(
                        model("LLM_GLM", "LLM", 1, "智谱 GLM", complete("openai", "glm-4-flash")),
                        model("LLM_Gemini", "LLM", 1, "Gemini", complete("gemini", "gemini")),
                        model("LLM_Disabled", "LLM", 0, "已停用", complete("openai", "disabled"))))
                .thenReturn(List.of(
                        model("Embedding_zhipu", "Embedding", 1, "智谱 Embedding 3",
                                completeEmbedding("embedding-3")),
                        model("Embedding_incomplete", "Embedding", 1, "缺少密钥",
                                completeEmbedding("embedding-3").set("api_key", ""))));

        ModelProviderDTO provider = providerWithReferenceFields();
        service.enrich(List.of(provider));

        JSONArray fields = JSONUtil.parseArray(provider.getFields());
        assertEquals("LLM_GLM", options(fields, "llm_model_id").getJSONObject(0).getStr("value"));
        assertEquals("智谱 GLM", options(fields, "llm_model_id").getJSONObject(0).getStr("label"));
        assertEquals(1, options(fields, "llm_model_id").size());
        assertEquals("Embedding_zhipu", options(fields, "embedding_model_id").getJSONObject(0).getStr("value"));
        assertEquals(1, options(fields, "embedding_model_id").size());
        assertFalse(provider.getFields().contains("glm-secret"));
        assertFalse(provider.getFields().contains("embedding-secret"));
    }

    @Test
    void leavesOtherProvidersUntouched() {
        ModelProviderDTO provider = new ModelProviderDTO();
        provider.setModelType("Memory");
        provider.setProviderCode("mem0ai");
        provider.setFields("[]");

        service.enrich(List.of(provider));

        assertEquals("[]", provider.getFields());
    }

    private ModelProviderDTO providerWithReferenceFields() {
        ModelProviderDTO provider = new ModelProviderDTO();
        provider.setModelType("Memory");
        provider.setProviderCode("tencentdb");
        provider.setFields("[{\"key\":\"memory_core_url\",\"label\":\"MemoryCore 地址\",\"type\":\"string\"},"
                + "{\"key\":\"llm_model_id\",\"label\":\"记忆 LLM\",\"type\":\"string\",\"options\":[]},"
                + "{\"key\":\"embedding_model_id\",\"label\":\"Embedding 模型\",\"type\":\"string\",\"options\":[]}]");
        return provider;
    }

    private ModelConfigEntity model(String id, String type, int enabled, String name, JSONObject config) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setModelType(type);
        model.setIsEnabled(enabled);
        model.setModelName(name);
        model.setConfigJson(config);
        return model;
    }

    private JSONObject complete(String type, String model) {
        return new JSONObject()
                .set("type", type)
                .set("base_url", "https://model.example/v1")
                .set("api_key", "glm-secret")
                .set("model_name", model);
    }

    private JSONObject completeEmbedding(String model) {
        return new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://embedding.example/v1")
                .set("api_key", "embedding-secret")
                .set("model_name", model)
                .set("dimensions", 1024)
                .set("send_dimensions", true);
    }

    private JSONArray options(JSONArray fields, String key) {
        return fields.stream()
                .map(JSONUtil::parseObj)
                .filter(field -> key.equals(field.getStr("key")))
                .findFirst()
                .orElseThrow()
                .getJSONArray("options");
    }
}
