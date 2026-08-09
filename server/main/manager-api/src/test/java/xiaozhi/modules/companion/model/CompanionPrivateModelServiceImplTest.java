package xiaozhi.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.model.dao.CompanionPrivateModelDao;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.dto.CompanionPrivateModelSaveDTO;
import xiaozhi.modules.companion.model.entity.CompanionPrivateModelEntity;
import xiaozhi.modules.companion.model.service.CompanionModelConnectionTester;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.model.service.CompanionModelTemplateService;
import xiaozhi.modules.companion.model.service.impl.CompanionPrivateModelServiceImpl;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderFieldVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.companion.model.vo.CompanionPrivateModelVO;
import xiaozhi.modules.companion.service.CompanionAuditService;

class CompanionPrivateModelServiceImplTest {
    private final CompanionPrivateModelDao dao = mock(CompanionPrivateModelDao.class);
    private final CompanionProfileModelDao bindingDao = mock(CompanionProfileModelDao.class);
    private final CompanionModelSecretService secrets = mock(CompanionModelSecretService.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final CompanionModelTemplateService templates = mock(CompanionModelTemplateService.class);
    private final CompanionModelConnectionTester tester = mock(CompanionModelConnectionTester.class);
    private final CompanionPrivateModelServiceImpl service = new CompanionPrivateModelServiceImpl(
            dao, bindingDao, secrets, audit, templates, tester);

    @Test
    void createEncryptsSecretMapAndReturnsOnlyConfiguredKeys() {
        CompanionPrivateModelSaveDTO request = request();
        request.setProviderTemplateId("SYSTEM_LLM_openai");
        request.setConfig(new LinkedHashMap<>(Map.of(
                "temperature", 0.4,
                "api_secret", "secret-from-wrong-map")));
        request.setSecrets(Map.of("api_key", "plain-secret"));
        when(templates.require("SYSTEM_LLM_openai", "LLM", "openai")).thenReturn(openAiTemplate());
        when(secrets.encryptMap(Map.of(
                "api_key", "plain-secret",
                "api_secret", "secret-from-wrong-map"))).thenReturn("v1:cipher-map");
        when(secrets.decryptMap("v1:cipher-map")).thenReturn(Map.of(
                "api_key", "plain-secret",
                "api_secret", "secret-from-wrong-map"));
        when(dao.insert(any(CompanionPrivateModelEntity.class))).thenAnswer(invocation -> {
            CompanionPrivateModelEntity entity = invocation.getArgument(0);
            entity.setId("private-1");
            return 1;
        });

        CompanionPrivateModelVO result = service.create(7L, request);

        assertEquals("private-1", result.getId());
        assertEquals("OpenAI", result.getVendorName());
        assertEquals("OpenAI 兼容", result.getProtocol());
        assertTrue(result.isCredentialRequired());
        assertTrue(result.isApiKeyConfigured());
        assertEquals(Set.of("api_key", "api_secret"), result.getConfiguredSecretKeys());
        assertFalse(result.toString().contains("plain-secret"));
        ArgumentCaptor<CompanionPrivateModelEntity> captor = ArgumentCaptor.forClass(CompanionPrivateModelEntity.class);
        verify(dao).insert(captor.capture());
        assertEquals(0.4, captor.getValue().getConfigJson().getDouble("temperature"));
        assertFalse(captor.getValue().getConfigJson().containsKey("api_secret"));
        assertEquals("v1:cipher-map", captor.getValue().getSecretConfigCiphertext());
    }

    @Test
    void updatePreservesOmittedSecretsAndClearsExplicitKeys() {
        CompanionPrivateModelEntity existing = entity(7L);
        existing.setSecretConfigCiphertext("v1:old-map");
        when(dao.selectOwnedForUpdate(7L, "private-1")).thenReturn(existing);
        when(dao.updateById(existing)).thenReturn(1);
        when(secrets.decryptMap("v1:old-map")).thenReturn(new LinkedHashMap<>(Map.of(
                "api_key", "old-key",
                "old_token", "old-token")));
        when(secrets.encryptMap(Map.of("api_key", "old-key", "api_secret", "new-secret")))
                .thenReturn("v1:new-map");
        when(secrets.decryptMap("v1:new-map")).thenReturn(Map.of(
                "api_key", "old-key",
                "api_secret", "new-secret"));
        CompanionPrivateModelSaveDTO request = request();
        request.setSecrets(Map.of("api_secret", "new-secret"));
        request.setClearSecretKeys(List.of("old_token"));

        CompanionPrivateModelVO result = service.update(7L, "private-1", request);

        assertEquals("v1:new-map", existing.getSecretConfigCiphertext());
        assertTrue(result.isApiKeyConfigured());
        assertEquals(Set.of("api_key", "api_secret"), result.getConfiguredSecretKeys());
        verify(secrets, never()).encrypt(any());
    }

    @Test
    void templateValidationRejectsWrongPublicFieldType() {
        CompanionPrivateModelSaveDTO request = request();
        request.setProviderTemplateId("SYSTEM_LLM_openai");
        request.setConfig(Map.of("temperature", "not-a-number"));
        request.setSecrets(Map.of("api_key", "test-key"));
        when(templates.require("SYSTEM_LLM_openai", "LLM", "openai")).thenReturn(openAiTemplate());

        RenException error = assertThrows(RenException.class, () -> service.create(7L, request));

        assertEquals("模型参数 temperature 类型无效", error.getMsg());
        verify(dao, never()).insert(any(CompanionPrivateModelEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionTestUsesMergedSavedAndSubmittedConfiguration() {
        CompanionPrivateModelEntity existing = entity(7L);
        existing.setApiUrl("https://saved.example/v1");
        existing.setConfigJson(new cn.hutool.json.JSONObject(Map.of("temperature", 0.4)));
        existing.setSecretConfigCiphertext("v1:saved-map");
        when(dao.selectOwned(7L, "private-1")).thenReturn(existing);
        when(secrets.decryptMap("v1:saved-map")).thenReturn(Map.of("api_key", "saved-key"));
        when(tester.test(org.mockito.ArgumentMatchers.eq("openai"), any())).thenReturn(
                new CompanionModelTestVO(true, 12, "连接成功"));
        CompanionPrivateModelSaveDTO request = request();
        request.setApiUrl("https://new.example/v1");
        request.setConfig(Map.of("temperature", 0.7));

        CompanionModelTestVO result = service.test(7L, "private-1", request);

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tester).test(org.mockito.ArgumentMatchers.eq("openai"), captor.capture());
        assertEquals("https://new.example/v1", captor.getValue().get("base_url"));
        assertEquals("saved-key", captor.getValue().get("api_key"));
        assertEquals(0.7, ((Number) captor.getValue().get("temperature")).doubleValue());
    }

    @Test
    void foreignOwnerCannotReadPrivateModel() {
        when(dao.selectOwned(7L, "private-1")).thenReturn(null);
        assertThrows(RenException.class, () -> service.get(7L, "private-1"));
    }

    @Test
    void referencedPrivateModelCannotBeDeleted() {
        when(dao.selectOwnedForUpdate(7L, "private-1")).thenReturn(entity(7L));
        when(bindingDao.countResourceUsage("private", "private-1")).thenReturn(2L);

        assertThrows(RenException.class, () -> service.delete(7L, "private-1"));

        verify(dao, never()).deleteById("private-1");
    }

    private CompanionPrivateModelSaveDTO request() {
        CompanionPrivateModelSaveDTO dto = new CompanionPrivateModelSaveDTO();
        dto.setModelType("LLM");
        dto.setName("My model");
        dto.setProviderCode("openai");
        dto.setVendorName("OpenAI");
        dto.setProtocol("OpenAI 兼容");
        dto.setCredentialRequired(true);
        dto.setApiUrl("https://api.example/v1");
        dto.setModelId("gpt-test");
        dto.setEnabled(1);
        return dto;
    }

    private CompanionModelProviderTemplateVO openAiTemplate() {
        CompanionModelProviderTemplateVO template = new CompanionModelProviderTemplateVO();
        template.setId("SYSTEM_LLM_openai");
        template.setModelType("LLM");
        template.setProviderCode("openai");
        template.setFields(List.of(
                field("base_url", "string", true, false),
                field("model_name", "string", true, false),
                field("api_key", "string", true, true),
                field("api_secret", "string", false, true),
                field("temperature", "number", false, false)));
        return template;
    }

    private CompanionModelProviderFieldVO field(String key, String type, boolean required, boolean secret) {
        CompanionModelProviderFieldVO field = new CompanionModelProviderFieldVO();
        field.setKey(key);
        field.setLabel(key);
        field.setType(type);
        field.setRequired(required);
        field.setSecret(secret);
        field.setOptions(List.of());
        return field;
    }

    private CompanionPrivateModelEntity entity(Long userId) {
        CompanionPrivateModelEntity entity = new CompanionPrivateModelEntity();
        entity.setId("private-1");
        entity.setUserId(userId);
        entity.setModelType("LLM");
        entity.setName("My model");
        entity.setProviderCode("openai");
        entity.setVendorName("OpenAI");
        entity.setProtocol("OpenAI 兼容");
        entity.setCredentialRequired(1);
        entity.setEnabled(1);
        return entity;
    }
}
