package xiaozhi.modules.model.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelConnectionTestService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.timbre.service.TimbreService;

class ModelControllerConnectionTest {
    private ModelConnectionTestService connectionTests;
    private ModelController controller;

    @BeforeEach
    void setUp() {
        connectionTests = mock(ModelConnectionTestService.class);
        controller = new ModelController(
                mock(ModelProviderService.class),
                mock(TimbreService.class),
                mock(ModelConfigService.class),
                mock(ConfigService.class),
                mock(AgentTemplateService.class),
                connectionTests);
    }

    @Test
    void testsUnsavedConfiguration() {
        ModelConfigBodyDTO body = new ModelConfigBodyDTO();
        when(connectionTests.test("LLM", "openai", null, body))
                .thenReturn(new CompanionModelTestVO(true, 14, "连接成功"));

        var result = controller.testNewModel("LLM", "openai", body);

        verify(connectionTests).test(eq("LLM"), eq("openai"), isNull(), any(ModelConfigBodyDTO.class));
        assertEquals("连接成功", result.getData().getMessage());
    }

    @Test
    void testsSavedConfiguration() {
        ModelConfigBodyDTO body = new ModelConfigBodyDTO();
        when(connectionTests.test("LLM", "openai", "LLM_DeepSeek", body))
                .thenReturn(new CompanionModelTestVO(false, 22, "服务返回 HTTP 401"));

        var result = controller.testSavedModel("LLM", "openai", "LLM_DeepSeek", body);

        verify(connectionTests).test("LLM", "openai", "LLM_DeepSeek", body);
        assertEquals("服务返回 HTTP 401", result.getData().getMessage());
    }
}
