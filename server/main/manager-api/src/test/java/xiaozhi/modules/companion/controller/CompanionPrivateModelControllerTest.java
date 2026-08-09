package xiaozhi.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.companion.model.dto.CompanionModelCopyDTO;
import xiaozhi.modules.companion.model.dto.CompanionGlobalModelCredentialSaveDTO;
import xiaozhi.modules.companion.model.service.CompanionGlobalModelCredentialService;
import xiaozhi.modules.companion.model.service.CompanionModelCatalogService;
import xiaozhi.modules.companion.model.service.CompanionModelTemplateService;
import xiaozhi.modules.companion.model.service.CompanionPrivateModelService;
import xiaozhi.modules.companion.model.vo.CompanionModelCatalogItemVO;
import xiaozhi.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.companion.model.vo.CompanionPrivateModelVO;

class CompanionPrivateModelControllerTest {

    @Test
    void catalogRoutesManagementAndSelectionViewsForCurrentUser() {
        CompanionModelCatalogService catalog = mock(CompanionModelCatalogService.class);
        CompanionPrivateModelController controller = controller(catalog, mock(CompanionModelTemplateService.class));
        List<CompanionModelCatalogItemVO> management = List.of(new CompanionModelCatalogItemVO());
        List<CompanionModelCatalogItemVO> selection = List.of(new CompanionModelCatalogItemVO());
        when(catalog.management(7L, "LLM")).thenReturn(management);
        when(catalog.selection(7L, "TTS")).thenReturn(selection);

        bindUser(7L);
        try {
            assertEquals(management, controller.catalog("LLM", "management").getData());
            assertEquals(selection, controller.catalog("TTS", "selection").getData());
        } finally {
            ThreadContext.unbindSubject();
        }
    }

    @Test
    void templatesAndCopyUseSharedServicesForCurrentUser() {
        CompanionModelCatalogService catalog = mock(CompanionModelCatalogService.class);
        CompanionModelTemplateService templates = mock(CompanionModelTemplateService.class);
        CompanionPrivateModelController controller = controller(catalog, templates);
        List<CompanionModelProviderTemplateVO> templateRows = List.of(new CompanionModelProviderTemplateVO());
        CompanionPrivateModelVO copied = new CompanionPrivateModelVO();
        when(templates.list("ASR")).thenReturn(templateRows);
        when(catalog.copy(7L, "global:g1", "我的识别模型")).thenReturn(copied);
        CompanionModelCopyDTO request = new CompanionModelCopyDTO();
        request.setReference("global:g1");
        request.setName("我的识别模型");

        bindUser(7L);
        try {
            assertEquals(templateRows, controller.templates("ASR").getData());
            assertEquals(copied, controller.copy(request).getData());
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(templates).list("ASR");
        verify(catalog).copy(7L, "global:g1", "我的识别模型");
    }

    @Test
    void catalogRejectsUnknownViewWithoutQueryingServices() {
        CompanionModelCatalogService catalog = mock(CompanionModelCatalogService.class);
        CompanionPrivateModelController controller = controller(catalog, mock(CompanionModelTemplateService.class));

        bindUser(7L);
        try {
            RenException error = assertThrows(RenException.class,
                    () -> controller.catalog("LLM", "unknown"));
            assertEquals("模型目录视图无效", error.getMsg());
        } finally {
            ThreadContext.unbindSubject();
        }
    }

    @Test
    void globalConfigurationRoutesUseCurrentUserAndNeverReturnSecretValues() {
        CompanionGlobalModelCredentialService credentials = mock(CompanionGlobalModelCredentialService.class);
        CompanionPrivateModelController controller = controller(mock(CompanionModelCatalogService.class),
                mock(CompanionModelTemplateService.class), credentials);
        CompanionGlobalModelCredentialSaveDTO request = new CompanionGlobalModelCredentialSaveDTO();
        request.setSecrets(java.util.Map.of("api_key", "plain-secret"));
        CompanionGlobalModelCredentialVO configured = new CompanionGlobalModelCredentialVO();
        configured.setGlobalModelId("LLM_DeepSeekLLM");
        configured.setConfiguredSecretKeys(List.of("api_key"));
        configured.setCredentialStatus("configured");
        CompanionModelTestVO testResult = new CompanionModelTestVO(true, 5, "连接成功");
        when(credentials.get(7L, "LLM_DeepSeekLLM")).thenReturn(configured);
        when(credentials.save(7L, "LLM_DeepSeekLLM", request)).thenReturn(configured);
        when(credentials.test(7L, "LLM_DeepSeekLLM", request)).thenReturn(testResult);

        bindUser(7L);
        try {
            assertEquals(configured, controller.getGlobalConfig("LLM_DeepSeekLLM").getData());
            assertEquals(configured, controller.saveGlobalConfig("LLM_DeepSeekLLM", request).getData());
            assertEquals(testResult, controller.testGlobalConfig("LLM_DeepSeekLLM", request).getData());
            assertEquals(false, configured.toString().contains("plain-secret"));
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(credentials).get(7L, "LLM_DeepSeekLLM");
        verify(credentials).save(7L, "LLM_DeepSeekLLM", request);
        verify(credentials).test(7L, "LLM_DeepSeekLLM", request);
    }

    private CompanionPrivateModelController controller(CompanionModelCatalogService catalog,
            CompanionModelTemplateService templates) {
        return controller(catalog, templates, mock(CompanionGlobalModelCredentialService.class));
    }

    private CompanionPrivateModelController controller(CompanionModelCatalogService catalog,
            CompanionModelTemplateService templates, CompanionGlobalModelCredentialService credentials) {
        return new CompanionPrivateModelController(mock(CompanionPrivateModelService.class), catalog, templates,
                credentials);
    }

    private void bindUser(Long userId) {
        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(userId);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
    }
}
