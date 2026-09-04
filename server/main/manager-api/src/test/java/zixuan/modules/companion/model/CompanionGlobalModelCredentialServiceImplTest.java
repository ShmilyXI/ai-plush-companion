package zixuan.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.hutool.json.JSONObject;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.model.dao.CompanionGlobalModelCredentialDao;
import zixuan.modules.companion.model.dto.CompanionGlobalModelCredentialSaveDTO;
import zixuan.modules.companion.model.entity.CompanionGlobalModelCredentialEntity;
import zixuan.modules.companion.model.service.CompanionModelConnectionTester;
import zixuan.modules.companion.model.service.CompanionModelPresetService;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.companion.model.service.impl.CompanionGlobalModelCredentialServiceImpl;
import zixuan.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import zixuan.modules.companion.model.vo.CompanionModelPresetVO;
import zixuan.modules.companion.model.vo.CompanionModelProviderFieldVO;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;

class CompanionGlobalModelCredentialServiceImplTest {
    private final CompanionGlobalModelCredentialDao dao = mock(CompanionGlobalModelCredentialDao.class);
    private final ModelConfigService globalModels = mock(ModelConfigService.class);
    private final CompanionModelPresetService presets = mock(CompanionModelPresetService.class);
    private final CompanionModelSecretService secrets = mock(CompanionModelSecretService.class);
    private final CompanionModelConnectionTester tester = mock(CompanionModelConnectionTester.class);
    private final CompanionGlobalModelCredentialServiceImpl service = new CompanionGlobalModelCredentialServiceImpl(
            dao, globalModels, presets, secrets, tester);

    @Test
    void savesOnlyCurrentUsersEncryptedCredentialAndReturnsConfiguredKeys() {
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(global("LLM_DeepSeekLLM"));
        when(presets.get("LLM_DeepSeekLLM")).thenReturn(requiredPreset("api_key"));
        when(secrets.encryptMap(Map.of("api_key", "secret-a"))).thenReturn("v1:cipher");
        when(secrets.decryptMap("v1:cipher")).thenReturn(Map.of("api_key", "secret-a"));
        when(dao.upsert(any())).thenReturn(1);

        CompanionGlobalModelCredentialVO saved = service.save(7L, "LLM_DeepSeekLLM",
                request(Map.of("api_key", "secret-a"), List.of()));

        assertEquals(List.of("api_key"), saved.getConfiguredSecretKeys());
        assertEquals("configured", saved.getCredentialStatus());
        assertFalse(saved.toString().contains("secret-a"));
        ArgumentCaptor<CompanionGlobalModelCredentialEntity> captor =
                ArgumentCaptor.forClass(CompanionGlobalModelCredentialEntity.class);
        verify(dao).upsert(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals("v1:cipher", captor.getValue().getSecretConfigCiphertext());
    }

    @Test
    void blankSubmittedSecretPreservesSavedValueAndExplicitClearRemovesIt() {
        CompanionGlobalModelCredentialEntity existing = credential(7L, "v1:old");
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(global("LLM_DeepSeekLLM"));
        when(presets.get("LLM_DeepSeekLLM")).thenReturn(requiredPreset("api_key", "access_token"));
        when(dao.selectOwned(7L, "LLM_DeepSeekLLM")).thenReturn(existing);
        when(secrets.decryptMap("v1:old")).thenReturn(new LinkedHashMap<>(Map.of(
                "api_key", "old-key", "access_token", "old-token")));
        when(secrets.encryptMap(Map.of("api_key", "old-key"))).thenReturn("v1:new");
        when(secrets.decryptMap("v1:new")).thenReturn(Map.of("api_key", "old-key"));
        when(dao.upsert(any())).thenReturn(1);

        CompanionGlobalModelCredentialSaveDTO request = request(Map.of("api_key", "   "), List.of("access_token"));
        CompanionGlobalModelCredentialVO saved = service.save(7L, "LLM_DeepSeekLLM", request);

        assertEquals(List.of("api_key"), saved.getConfiguredSecretKeys());
        verify(secrets).encryptMap(Map.of("api_key", "old-key"));
    }

    @Test
    void readsCredentialsWithUserAndModelScope() {
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(global("LLM_DeepSeekLLM"));
        when(presets.get("LLM_DeepSeekLLM")).thenReturn(requiredPreset("api_key"));
        when(dao.selectOwned(8L, "LLM_DeepSeekLLM")).thenReturn(null);

        CompanionGlobalModelCredentialVO result = service.get(8L, "LLM_DeepSeekLLM");

        assertEquals("missing", result.getCredentialStatus());
        verify(dao).selectOwned(8L, "LLM_DeepSeekLLM");
        verify(dao, never()).selectById(any());
    }

    @Test
    void rejectsUnknownGlobalModelBeforeWriting() {
        when(globalModels.selectById("missing")).thenReturn(null);

        assertThrows(RenException.class, () -> service.save(7L, "missing", request(Map.of(), List.of())));

        verify(dao, never()).upsert(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionTestUsesTemporaryUserValuesWithoutPersistingOrLeakingAdminSecret() {
        ModelConfigEntity global = global("LLM_DeepSeekLLM");
        global.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://admin.example/v1")
                .set("model_name", "admin-model")
                .set("api_key", "admin-secret")
                .set("temperature", 0.4));
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(global);
        when(presets.get("LLM_DeepSeekLLM")).thenReturn(requiredPreset("api_key"));
        when(dao.selectOwned(7L, "LLM_DeepSeekLLM")).thenReturn(null);
        when(tester.test(eq("openai"), any())).thenReturn(new CompanionModelTestVO(true, 4, "连接成功"));
        CompanionGlobalModelCredentialSaveDTO request = request(Map.of("api_key", "user-secret"), List.of());
        request.setApiUrl("https://user.example/v1");
        request.setModelId("user-model");

        CompanionModelTestVO result = service.test(7L, "LLM_DeepSeekLLM", request);

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tester).test(eq("openai"), captor.capture());
        assertEquals("user-secret", captor.getValue().get("api_key"));
        assertEquals("https://user.example/v1", captor.getValue().get("base_url"));
        assertEquals("user-model", captor.getValue().get("model_name"));
        assertFalse(captor.getValue().containsValue("admin-secret"));
        verify(dao, never()).upsert(any());
    }

    private CompanionGlobalModelCredentialSaveDTO request(Map<String, Object> values, List<String> clears) {
        CompanionGlobalModelCredentialSaveDTO dto = new CompanionGlobalModelCredentialSaveDTO();
        dto.setSecrets(values);
        dto.setClearSecretKeys(clears);
        return dto;
    }

    private ModelConfigEntity global(String id) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(id);
        entity.setModelType("LLM");
        entity.setModelCode("DeepSeekLLM");
        entity.setIsEnabled(1);
        return entity;
    }

    private CompanionModelPresetVO requiredPreset(String... keys) {
        CompanionModelPresetVO preset = new CompanionModelPresetVO();
        preset.setGlobalModelId("LLM_DeepSeekLLM");
        preset.setCredentialRequirement("required");
        preset.setCredentialFields(java.util.Arrays.stream(keys).map(this::field).toList());
        return preset;
    }

    private CompanionModelProviderFieldVO field(String key) {
        CompanionModelProviderFieldVO field = new CompanionModelProviderFieldVO();
        field.setKey(key);
        field.setLabel(key);
        field.setType("string");
        field.setRequired(true);
        field.setSecret(true);
        field.setOptions(List.of());
        return field;
    }

    private CompanionGlobalModelCredentialEntity credential(Long userId, String ciphertext) {
        CompanionGlobalModelCredentialEntity entity = new CompanionGlobalModelCredentialEntity();
        entity.setId("credential-1");
        entity.setUserId(userId);
        entity.setGlobalModelId("LLM_DeepSeekLLM");
        entity.setSecretConfigCiphertext(ciphertext);
        return entity;
    }
}
