package xiaozhi.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.companion.model.dao.CompanionPrivateModelDao;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.dto.CompanionPrivateModelSaveDTO;
import xiaozhi.modules.companion.model.entity.CompanionPrivateModelEntity;
import xiaozhi.modules.companion.model.service.CompanionModelTemplateService;
import xiaozhi.modules.companion.model.service.CompanionPrivateModelService;
import xiaozhi.modules.companion.model.service.CompanionGlobalModelCredentialService;
import xiaozhi.modules.companion.model.service.CompanionModelPresetService;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.model.service.impl.CompanionModelCatalogServiceImpl;
import xiaozhi.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import xiaozhi.modules.companion.model.vo.CompanionModelCatalogItemVO;
import xiaozhi.modules.companion.model.vo.CompanionModelPresetVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderFieldVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.companion.model.vo.CompanionPrivateModelVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

class CompanionModelCatalogServiceImplTest {
    private final ModelConfigService globalModels = mock(ModelConfigService.class);
    private final CompanionPrivateModelDao privateModels = mock(CompanionPrivateModelDao.class);
    private final CompanionProfileModelDao bindings = mock(CompanionProfileModelDao.class);
    private final CompanionPrivateModelService privateService = mock(CompanionPrivateModelService.class);
    private final CompanionModelTemplateService templates = mock(CompanionModelTemplateService.class);
    private final CompanionModelPresetService presets = mock(CompanionModelPresetService.class);
    private final CompanionGlobalModelCredentialService credentials = mock(CompanionGlobalModelCredentialService.class);
    private final CompanionModelSecretService secrets = mock(CompanionModelSecretService.class);
    private final CompanionModelCatalogServiceImpl service = new CompanionModelCatalogServiceImpl(
            globalModels, privateModels, bindings, privateService, templates, presets, credentials, secrets);

    @Test
    void managementCombinesGlobalAndOwnedModelsAndSelectionKeepsUnavailableEnabledRows() {
        when(globalModels.getModelsByType("LLM")).thenReturn(List.of(
                global("g1", "系统启用", 1), global("g2", "系统停用", 0)));
        when(privateModels.selectOwnedByType(7L, "LLM")).thenReturn(List.of(
                privateModel("p1", "个人启用", 1), privateModel("p2", "个人停用", 0)));
        when(presets.get(anyString())).thenAnswer(invocation -> unknownPreset(invocation.getArgument(0)));
        when(credentials.get(org.mockito.ArgumentMatchers.eq(7L), anyString()))
                .thenAnswer(invocation -> credential(invocation.getArgument(1), "unknown"));

        List<CompanionModelCatalogItemVO> management = service.management(7L, "LLM");
        List<CompanionModelCatalogItemVO> selection = service.selection(7L, "LLM");

        assertEquals(List.of("global:g1", "global:g2", "private:p1", "private:p2"),
                management.stream().map(CompanionModelCatalogItemVO::getReference).toList());
        assertEquals(List.of("global:g1", "private:p1"),
                selection.stream().map(CompanionModelCatalogItemVO::getReference).toList());
        assertEquals(List.of("view", "configure", "test", "copy"), management.getFirst().getActions());
        assertFalse(selection.getFirst().isEnabled());
        assertEquals("请先在模型管理中配置凭据", selection.getFirst().getUnavailableReason());
        assertFalse(management.get(2).getActions().isEmpty());
    }

    @Test
    void globalCatalogShowsRealVendorEffectiveApiAndCredentialStates() {
        ModelConfigEntity deepSeek = global("LLM_DeepSeekLLM", "DeepSeek", 1);
        deepSeek.setConfigJson(new JSONObject().set("type", "openai")
                .set("base_url", "https://upstream.example/v1").set("model_name", "deepseek-chat"));
        ModelConfigEntity ollama = global("LLM_OllamaLLM", "Ollama", 1);
        ModelConfigEntity unmapped = global("LLM_Unmapped", "未映射", 1);
        when(globalModels.getModelsByType("LLM")).thenReturn(List.of(deepSeek, ollama, unmapped));
        when(globalModels.selectById("LLM_DeepSeekLLM")).thenReturn(deepSeek);
        when(privateModels.selectOwnedByType(7L, "LLM")).thenReturn(List.of());
        when(presets.get("LLM_DeepSeekLLM")).thenReturn(preset("LLM_DeepSeekLLM", "deepseek", "DeepSeek",
                "OpenAI 兼容", "https://api.deepseek.com", "required"));
        when(presets.get("LLM_OllamaLLM")).thenReturn(preset("LLM_OllamaLLM", "ollama", "Ollama",
                "OpenAI 兼容", "http://localhost:11434/v1", "not_required"));
        when(presets.get("LLM_Unmapped")).thenReturn(unknownPreset("LLM_Unmapped"));
        CompanionGlobalModelCredentialVO deepSeekCredential = credential("LLM_DeepSeekLLM", "missing");
        deepSeekCredential.setApiUrl("https://proxy.example/v1");
        when(credentials.get(7L, "LLM_DeepSeekLLM")).thenReturn(deepSeekCredential);
        when(credentials.get(7L, "LLM_OllamaLLM")).thenReturn(credential("LLM_OllamaLLM", "not_required"));
        when(credentials.get(7L, "LLM_Unmapped")).thenReturn(credential("LLM_Unmapped", "unknown"));

        List<CompanionModelCatalogItemVO> rows = service.management(7L, "LLM");

        assertEquals("DeepSeek", rows.getFirst().getVendorName());
        assertEquals("OpenAI 兼容", rows.getFirst().getProtocol());
        assertEquals("https://proxy.example/v1", rows.getFirst().getApiUrl());
        assertEquals("missing", rows.getFirst().getCredentialStatus());
        assertEquals("not_required", rows.get(1).getCredentialStatus());
        assertEquals("unknown", rows.get(2).getCredentialStatus());
        assertEquals(List.of("view", "configure", "test", "copy"), rows.getFirst().getActions());
        assertFalse(service.isSelectable(7L, "LLM_DeepSeekLLM"));
    }

    @Test
    void copyingGlobalModelKeepsPublicConfigurationAndDropsCredentials() {
        ModelConfigEntity source = global("g1", "系统模型", 1);
        source.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://api.example/v1")
                .set("model_name", "gpt-test")
                .set("api_key", "admin-secret")
                .set("temperature", 0.4));
        when(globalModels.selectById("g1")).thenReturn(source);
        when(presets.get("g1")).thenReturn(preset("g1", "openai", "OpenAI",
                "OpenAI 兼容", "https://api.example/v1", "required"));
        when(templates.isSecretKey("api_key")).thenReturn(true);
        CompanionModelProviderTemplateVO template = new CompanionModelProviderTemplateVO();
        template.setId("SYSTEM_LLM_openai");
        template.setProviderCode("openai");
        when(templates.list("LLM")).thenReturn(List.of(template));
        when(privateService.create(any(), any())).thenReturn(new CompanionPrivateModelVO());

        service.copy(7L, "global:g1", null);

        ArgumentCaptor<CompanionPrivateModelSaveDTO> captor = ArgumentCaptor.forClass(CompanionPrivateModelSaveDTO.class);
        verify(privateService).create(org.mockito.ArgumentMatchers.eq(7L), captor.capture());
        CompanionPrivateModelSaveDTO request = captor.getValue();
        assertEquals("OpenAI", request.getVendorName());
        assertEquals("OpenAI 兼容", request.getProtocol());
        assertEquals("SYSTEM_LLM_openai", request.getProviderTemplateId());
        assertEquals("https://api.example/v1", request.getApiUrl());
        assertEquals("gpt-test", request.getModelId());
        assertEquals(0.4, ((Number) request.getConfig().get("temperature")).doubleValue());
        assertFalse(request.getConfig().containsKey("api_key"));
    }

    private ModelConfigEntity global(String id, String name, int enabled) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(id);
        entity.setModelType("LLM");
        entity.setModelName(name);
        entity.setModelCode("openai");
        entity.setIsEnabled(enabled);
        entity.setIsDefault("g1".equals(id) ? 1 : 0);
        entity.setSort("g1".equals(id) ? 1 : 2);
        return entity;
    }

    private CompanionPrivateModelEntity privateModel(String id, String name, int enabled) {
        CompanionPrivateModelEntity entity = new CompanionPrivateModelEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setModelType("LLM");
        entity.setName(name);
        entity.setProviderCode("openai");
        entity.setVendorName("自定义厂商");
        entity.setProtocol("OpenAI 兼容");
        entity.setCredentialRequired(0);
        entity.setEnabled(enabled);
        return entity;
    }

    private CompanionModelPresetVO preset(String id, String vendorCode, String vendorName, String protocol,
            String apiUrl, String requirement) {
        CompanionModelPresetVO preset = new CompanionModelPresetVO();
        preset.setGlobalModelId(id);
        preset.setVendorCode(vendorCode);
        preset.setVendorName(vendorName);
        preset.setProtocol(protocol);
        preset.setDefaultApiUrl(apiUrl);
        preset.setCredentialRequirement(requirement);
        CompanionModelProviderFieldVO field = new CompanionModelProviderFieldVO();
        field.setKey("api_key");
        field.setLabel("API Key");
        field.setType("string");
        field.setRequired(true);
        field.setSecret(true);
        field.setOptions(List.of());
        preset.setCredentialFields("required".equals(requirement) ? List.of(field) : List.of());
        preset.setSetupGuide(List.of("创建密钥"));
        return preset;
    }

    private CompanionModelPresetVO unknownPreset(String id) {
        return preset(id, null, null, null, null, "unknown");
    }

    private CompanionGlobalModelCredentialVO credential(String id, String status) {
        CompanionGlobalModelCredentialVO credential = new CompanionGlobalModelCredentialVO();
        credential.setGlobalModelId(id);
        credential.setCredentialStatus(status);
        credential.setCredentialConfigured("configured".equals(status) || "not_required".equals(status));
        credential.setConfiguredSecretKeys(List.of());
        return credential;
    }
}
