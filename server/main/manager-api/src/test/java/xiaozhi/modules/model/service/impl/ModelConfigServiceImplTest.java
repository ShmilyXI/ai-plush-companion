package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.hutool.json.JSONObject;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.SensitiveDataUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelProviderService;

class ModelConfigServiceImplTest {

    @Test
    void adminMutableUpdatePersistsAllowedFieldsAndPreservesModelIdentityAndSecrets() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        ModelConfigEntity original = new ModelConfigEntity();
        original.setId("m1");
        original.setModelType("LLM");
        original.setModelCode("AliLLM");
        original.setModelName("旧名称");
        original.setDocLink("https://docs.example/original");
        original.setIsDefault(1);
        original.setIsEnabled(1);
        original.setSort(1);
        original.setRemark("旧备注");
        original.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("api_key", "secret-value")
                .set("temperature", 0.2));
        when(dao.selectById("m1")).thenReturn(original);
        when(dao.updateById(any(ModelConfigEntity.class))).thenReturn(1);
        ModelConfigServiceImpl service = new ModelConfigServiceImpl(
                dao, mock(ModelProviderService.class), mock(RedisUtils.class), mock(AgentDao.class));

        service.editMutable("m1", "新名称", 0, "新备注", 9,
                new JSONObject().set("temperature", 0.8));

        ArgumentCaptor<ModelConfigEntity> saved = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(dao).updateById(saved.capture());
        ModelConfigEntity entity = saved.getValue();
        assertEquals("LLM", entity.getModelType());
        assertEquals("AliLLM", entity.getModelCode());
        assertEquals("https://docs.example/original", entity.getDocLink());
        assertEquals(1, entity.getIsDefault());
        assertEquals("新名称", entity.getModelName());
        assertEquals(0, entity.getIsEnabled());
        assertEquals(9, entity.getSort());
        assertEquals("新备注", entity.getRemark());
        assertEquals("openai", entity.getConfigJson().getStr("type"));
        assertEquals("secret-value", entity.getConfigJson().getStr("api_key"));
        assertEquals(0.8, entity.getConfigJson().getDouble("temperature"));
        verify(dao, org.mockito.Mockito.never()).insert(any(ModelConfigEntity.class));
    }

    @Test
    void adminMutableUpdateCanAddConfigurationWhenTheModelHadNone() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        ModelConfigEntity original = new ModelConfigEntity();
        original.setId("m2");
        original.setModelType("ASR");
        original.setModelCode("SafeASR");
        original.setModelName("旧名称");
        original.setIsEnabled(1);
        when(dao.selectById("m2")).thenReturn(original);
        when(dao.updateById(any(ModelConfigEntity.class))).thenReturn(1);
        ModelConfigServiceImpl service = new ModelConfigServiceImpl(
                dao, mock(ModelProviderService.class), mock(RedisUtils.class), mock(AgentDao.class));

        service.editMutable("m2", "新名称", 1, null, 0,
                new JSONObject().set("language", "zh-CN"));

        ArgumentCaptor<ModelConfigEntity> saved = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(dao).updateById(saved.capture());
        assertEquals("zh-CN", saved.getValue().getConfigJson().getStr("language"));
    }

    @Test
    void mutableUpdatePreservesAllOmittedCredentialFieldsAndMasksTheResponse() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        ModelConfigEntity original = new ModelConfigEntity();
        original.setId("m3");
        original.setModelType("LLM");
        original.setModelCode("OpenAI");
        original.setModelName("旧名称");
        original.setIsEnabled(1);
        original.setConfigJson(new JSONObject()
                .set("authorization", "Bearer plaintext")
                .set("api_secret", "api-secret-value")
                .set("password", "password-value")
                .set("private_key", "private-key-value")
                .set("nested", new JSONObject().set("credential", "nested-value"))
                .set("max_tokens", 2048)
                .set("max_new_tokens", 1024));
        when(dao.selectById("m3")).thenReturn(original);
        when(dao.updateById(any(ModelConfigEntity.class))).thenReturn(1);
        ModelConfigServiceImpl service = new ModelConfigServiceImpl(
                dao, mock(ModelProviderService.class), mock(RedisUtils.class), mock(AgentDao.class));

        var response = service.editMutable("m3", "新名称", 1, null, 0,
                new JSONObject().set("max_tokens", 4096).set("max_new_tokens", 2048));

        ArgumentCaptor<ModelConfigEntity> saved = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(dao).updateById(saved.capture());
        JSONObject config = saved.getValue().getConfigJson();
        assertEquals("Bearer plaintext", config.getStr("authorization"));
        assertEquals("api-secret-value", config.getStr("api_secret"));
        assertEquals("password-value", config.getStr("password"));
        assertEquals("private-key-value", config.getStr("private_key"));
        assertEquals("nested-value", config.getJSONObject("nested").getStr("credential"));
        assertEquals(4096, config.getInt("max_tokens"));
        assertEquals(2048, config.getInt("max_new_tokens"));
        String returned = response.getConfigJson().toString();
        assertFalse(returned.contains("Bearer plaintext"));
        assertFalse(returned.contains("api-secret-value"));
        assertFalse(returned.contains("password-value"));
        assertFalse(returned.contains("private-key-value"));
        assertFalse(returned.contains("nested-value"));
        assertEquals(4096, response.getConfigJson().getInt("max_tokens"));
        assertEquals(2048, response.getConfigJson().getInt("max_new_tokens"));
    }

    @Test
    void nativeEditPreservesCredentialsOmittedByTheRedactingFrontend() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        ModelProviderService providers = mock(ModelProviderService.class);
        when(providers.getList("LLM", "openai")).thenReturn(List.of(new ModelProviderDTO()));
        ModelConfigEntity original = new ModelConfigEntity();
        original.setId("m4");
        original.setModelType("LLM");
        original.setModelCode("OpenAI");
        original.setModelName("旧名称");
        original.setIsEnabled(1);
        original.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("authorization", "Bearer plaintext")
                .set("api_secret", "api-secret-value")
                .set("nested", new JSONObject().set("password", "password-value"))
                .set("max_tokens", 2048));
        when(dao.selectById("m4")).thenReturn(original);
        when(dao.updateById(any(ModelConfigEntity.class))).thenReturn(1);
        ModelConfigServiceImpl service = new ModelConfigServiceImpl(
                dao, providers, mock(RedisUtils.class), mock(AgentDao.class));
        ModelConfigBodyDTO body = new ModelConfigBodyDTO();
        body.setModelName("新名称");
        body.setIsEnabled(1);
        body.setSort(0);
        body.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("nested", new JSONObject())
                .set("max_tokens", 4096));

        service.edit("LLM", "openai", "m4", body);

        ArgumentCaptor<ModelConfigEntity> saved = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(dao).updateById(saved.capture());
        JSONObject config = saved.getValue().getConfigJson();
        assertEquals("Bearer plaintext", config.getStr("authorization"));
        assertEquals("api-secret-value", config.getStr("api_secret"));
        assertEquals("password-value", config.getJSONObject("nested").getStr("password"));
        assertEquals(4096, config.getInt("max_tokens"));
    }

    @Test
    void sensitiveFieldContractDoesNotClassifyTokenLimitsAsCredentials() {
        assertFalse(SensitiveDataUtils.isSensitiveField("max_tokens"));
        assertFalse(SensitiveDataUtils.isSensitiveField("max_new_tokens"));
    }

    @Test
    void shortCredentialsAreFullyMaskedWithoutRecoverableCharacters() {
        JSONObject masked = SensitiveDataUtils.maskSensitiveFields(new JSONObject()
                .set("authorization", "a")
                .set("api_secret", "bc")
                .set("password", "def")
                .set("private_key", "ghij"));

        assertEquals("****", masked.getStr("authorization"));
        assertEquals("****", masked.getStr("api_secret"));
        assertEquals("****", masked.getStr("password"));
        assertEquals("****", masked.getStr("private_key"));
    }
}
