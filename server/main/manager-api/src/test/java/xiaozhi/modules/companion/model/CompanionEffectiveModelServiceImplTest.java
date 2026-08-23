package xiaozhi.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.model.dao.CompanionPrivateModelDao;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.entity.CompanionProfileModelEntity;
import xiaozhi.modules.companion.model.service.CompanionModelCatalogService;
import xiaozhi.modules.companion.model.service.CompanionGlobalModelCredentialService;
import xiaozhi.modules.companion.model.service.CompanionModelPresetService;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.model.service.impl.CompanionEffectiveModelServiceImpl;
import xiaozhi.modules.companion.model.vo.CompanionEffectiveModelVO;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;
import xiaozhi.modules.companion.model.vo.GlobalModelCredentialRuntime;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

class CompanionEffectiveModelServiceImplTest {
    private final CompanionPrivateModelDao privateDao = mock(CompanionPrivateModelDao.class);
    private final CompanionProfileModelDao bindingDao = mock(CompanionProfileModelDao.class);
    private final ModelConfigService globalModels = mock(ModelConfigService.class);
    private final CompanionModelSecretService secrets = mock(CompanionModelSecretService.class);
    private final CompanionModelCatalogService catalog = mock(CompanionModelCatalogService.class);
    private final CompanionGlobalModelCredentialService globalCredentials =
            mock(CompanionGlobalModelCredentialService.class);
    private final CompanionModelPresetService presets = mock(CompanionModelPresetService.class);
    private final CompanionEffectiveModelServiceImpl service = new CompanionEffectiveModelServiceImpl(
            privateDao, bindingDao, globalModels, secrets, catalog, globalCredentials, presets);

    @Test
    void profileOptionsContainOnlyEnabledXiaozhiModelsWithRealProviderCodes() {
        ModelConfigEntity llm = model("LLM_DeepSeek", "LLM", "DeepSeek", "DeepSeekLLM", "openai");
        ModelConfigEntity tts = model("TTS_Edge", "TTS", "Edge", "EdgeTTS", null);
        tts.setIsDefault(1);
        when(globalModels.getEnabledModelsByType("LLM")).thenReturn(List.of(llm));
        when(globalModels.getEnabledModelsByType("TTS")).thenReturn(List.of(tts));

        List<CompanionModelOptionVO> result = service.options(7L);

        CompanionModelOptionVO llmOption = result.stream().filter(item -> "LLM".equals(item.getModelType()))
                .findFirst().orElseThrow();
        assertEquals("LLM_DeepSeek", llmOption.getId());
        assertEquals("global", llmOption.getSource());
        assertEquals("openai", llmOption.getProviderCode());
        assertTrue(llmOption.isEnabled());
        assertTrue(result.stream().allMatch(option -> "global".equals(option.getSource())));
        CompanionModelOptionVO ttsOption = result.stream().filter(item -> "TTS".equals(item.getModelType()))
                .findFirst().orElseThrow();
        assertEquals("EdgeTTS", ttsOption.getProviderCode());
        assertEquals("not_required", ttsOption.getCredentialStatus());
        assertTrue(ttsOption.getIsDefault());
        assertTrue(new ObjectMapper().valueToTree(ttsOption).get("isDefault").asBoolean());
        verify(catalog, never()).selection(7L, "LLM");
        verify(privateDao, never()).selectOwnedByType(7L, "LLM");
    }

    @Test
    void runtimeGlobalModelUsesOnlyCurrentUsersCredentialOverlay() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM");
        binding.setSourceType("global");
        binding.setResourceId("LLM_DeepSeekLLM");
        binding.setOverrideJson(new JSONObject().set("temperature", 0.8));
        ModelConfigEntity resource = new ModelConfigEntity();
        resource.setId("LLM_DeepSeekLLM");
        resource.setModelType("LLM");
        resource.setIsEnabled(1);
        resource.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://admin.example/v1")
                .set("model_name", "admin-model")
                .set("api_key", "admin-secret")
                .set("temperature", 0.2));
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(resource);
        when(globalCredentials.runtime(7L, "LLM_DeepSeekLLM")).thenReturn(
                new GlobalModelCredentialRuntime("https://proxy.example/v1", "user-model",
                        Map.of("api_key", "user-secret")));
        when(presets.credentialKeys("LLM_DeepSeekLLM")).thenReturn(java.util.Set.of("api_key"));
        when(catalog.isSelectable(7L, "LLM_DeepSeekLLM")).thenReturn(true);

        CompanionRuntimeModel runtime = service.resolveRuntime(7L, profile).get("LLM");

        assertEquals("global:LLM_DeepSeekLLM", runtime.getId());
        assertEquals("user-secret", runtime.getConfig().get("api_key"));
        assertEquals("https://proxy.example/v1", runtime.getConfig().get("base_url"));
        assertEquals("user-model", runtime.getConfig().get("model_name"));
        assertEquals(0.8, ((Number) runtime.getConfig().get("temperature")).doubleValue());
        assertFalse(runtime.getConfig().containsValue("admin-secret"));
    }

    @Test
    void playgroundRuntimeFallsBackToLegacyTtsModelWhenProfileBindingIsDefault() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        profile.setTtsModelId("TTS_Huoshan");
        ModelConfigEntity resource = model("TTS_Huoshan", "TTS", "火山 TTS", "HuoshanDoubleStreamTTS", "huoshan_double_stream");
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of());
        when(globalModels.selectById("TTS_Huoshan")).thenReturn(resource);
        when(globalCredentials.runtime(7L, "TTS_Huoshan")).thenReturn(new GlobalModelCredentialRuntime(null, null, Map.of()));
        when(catalog.isSelectable(7L, "TTS_Huoshan")).thenReturn(true);
        when(presets.credentialKeys("TTS_Huoshan")).thenReturn(java.util.Set.of());

        CompanionRuntimeModel runtime = service.resolveRuntimeForPlayground(7L, profile, Map.of()).get("TTS");

        assertEquals("global:TTS_Huoshan", runtime.getId());
        assertEquals("huoshan_double_stream", runtime.getConfig().get("type"));
    }

    @Test
    void playgroundRuntimeUsesExplicitSelectedModelOverLegacyProfileField() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        profile.setTtsModelId("TTS_Old");
        ModelConfigEntity resource = model("TTS_New", "TTS", "新 TTS", "EdgeTTS", "edge");
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of());
        when(globalModels.selectById("TTS_New")).thenReturn(resource);
        when(globalCredentials.runtime(7L, "TTS_New")).thenReturn(new GlobalModelCredentialRuntime(null, null, Map.of()));
        when(catalog.isSelectable(7L, "TTS_New")).thenReturn(true);
        when(presets.credentialKeys("TTS_New")).thenReturn(java.util.Set.of());

        CompanionRuntimeModel runtime = service.resolveRuntimeForPlayground(7L, profile, Map.of("TTS", "TTS_New")).get("TTS");

        assertEquals("global:TTS_New", runtime.getId());
        assertEquals("edge", runtime.getConfig().get("type"));
    }

    @Test
    void runtimeSkipsGlobalModelWhenAccountCredentialsAreMissing() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM");
        binding.setSourceType("global");
        binding.setResourceId("LLM_DeepSeekLLM");
        ModelConfigEntity resource = new ModelConfigEntity();
        resource.setId("LLM_DeepSeekLLM");
        resource.setModelType("LLM");
        resource.setIsEnabled(1);
        resource.setConfigJson(new JSONObject().set("api_key", "admin-secret"));
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(resource);
        when(catalog.isSelectable(7L, "LLM_DeepSeekLLM")).thenReturn(false);

        assertNull(service.resolveRuntime(7L, profile).get("LLM"));
        verify(globalCredentials, never()).runtime(7L, "LLM_DeepSeekLLM");
    }

    @Test
    void privateBindingIsReturnedOnlyAsUnavailableMigrationMarker() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1"); profile.setUserId(7L);
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM"); binding.setSourceType("private"); binding.setResourceId("private-1");
        binding.setOverrideJson(new JSONObject().set("displayName", "旧版专属模型").set("model", "role-model"));
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));

        List<CompanionEffectiveModelVO> result = service.resolveForDisplay(7L, profile);

        assertEquals(1, result.size());
        assertEquals("private", result.get(0).getSource());
        assertEquals("旧版专属模型", result.get(0).getName());
        assertFalse(result.get(0).isEnabled());
        assertEquals("旧个人模型已停用，请重新选择", result.get(0).getUnavailableReason());
        verify(privateDao, never()).selectOwned(7L, "private-1");
        verify(secrets, never()).decryptMap(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unnamedPrivateBindingUsesStableMigrationName() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM"); binding.setSourceType("private"); binding.setResourceId("private-1");
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));

        CompanionEffectiveModelVO result = service.resolveForDisplay(7L, profile).getFirst();

        assertEquals("旧个人模型", result.getName());
        assertFalse(result.isEnabled());
    }

    @Test
    void disabledGlobalBindingIsUnavailableAndExcludedFromRuntime() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM"); binding.setSourceType("global"); binding.setResourceId("LLM_Disabled");
        ModelConfigEntity resource = model("LLM_Disabled", "LLM", "已停用模型", "DisabledLLM", "openai");
        resource.setIsEnabled(0);
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));
        when(globalModels.selectById("LLM_Disabled")).thenReturn(resource);

        CompanionEffectiveModelVO display = service.resolveForDisplay(7L, profile).getFirst();

        assertFalse(display.isEnabled());
        assertEquals("模型已停用，请重新选择", display.getUnavailableReason());
        assertNull(service.resolveRuntime(7L, profile).get("LLM"));
        verify(globalCredentials, never()).runtime(7L, "LLM_Disabled");
    }

    @Test
    void wrongTypeGlobalBindingIsUnavailableAndExcludedFromRuntime() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM"); binding.setSourceType("global"); binding.setResourceId("TTS_Wrong");
        ModelConfigEntity resource = model("TTS_Wrong", "TTS", "错类型模型", "WrongTTS", "edge");
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));
        when(globalModels.selectById("TTS_Wrong")).thenReturn(resource);

        CompanionEffectiveModelVO display = service.resolveForDisplay(7L, profile).getFirst();

        assertFalse(display.isEnabled());
        assertEquals("模型类型不匹配，请重新选择", display.getUnavailableReason());
        assertNull(service.resolveRuntime(7L, profile).get("LLM"));
        verify(globalCredentials, never()).runtime(7L, "TTS_Wrong");
    }

    @Test
    void disabledLegacyModelIdIsReturnedAsUnavailable() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-1");
        profile.setLlmModelId("LLM_Disabled");
        ModelConfigEntity resource = model("LLM_Disabled", "LLM", "已停用模型", "DisabledLLM", "openai");
        resource.setIsEnabled(0);
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of());
        when(globalModels.selectById("LLM_Disabled")).thenReturn(resource);

        CompanionEffectiveModelVO display = service.resolveForDisplay(7L, profile).getFirst();

        assertFalse(display.isEnabled());
        assertEquals("模型已停用，请重新选择", display.getUnavailableReason());
    }

    @Test
    void runtimeSkipsPrivateBindingWithoutReadingOrDecryptingIt() {
        AgentEntity profile = new AgentEntity(); profile.setId("profile-1"); profile.setUserId(7L);
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM"); binding.setSourceType("private"); binding.setResourceId("private-1");
        when(bindingDao.selectByAgentId("profile-1")).thenReturn(List.of(binding));

        CompanionRuntimeModel runtime = service.resolveRuntime(7L, profile).get("LLM");

        assertNull(runtime);
        verify(privateDao, never()).selectOwned(7L, "private-1");
        verify(secrets, never()).decryptMap(org.mockito.ArgumentMatchers.any());
    }

    private ModelConfigEntity model(String id, String type, String name, String modelCode, String providerCode) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setModelType(type);
        model.setModelName(name);
        model.setModelCode(modelCode);
        model.setIsEnabled(1);
        if (providerCode != null) model.setConfigJson(new JSONObject().set("type", providerCode));
        return model;
    }
}
