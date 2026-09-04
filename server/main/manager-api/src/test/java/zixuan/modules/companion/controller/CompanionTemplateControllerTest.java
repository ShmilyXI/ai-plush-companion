package zixuan.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentTemplateService;

class CompanionTemplateControllerTest {
    @Test
    void normalCatalogReturnsOnlySafeCompanionTemplateFields() {
        AgentTemplateService templates = mock(AgentTemplateService.class);
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-a");
        template.setAgentCode("zixuan-companion");
        template.setAgentName("治愈伙伴");
        template.setSystemPrompt("secret prompt");
        template.setLlmModelId("internal-model");
        template.setCompanionCueConfig("{\"laugh\":\"laugh.wav\"}");
        when(templates.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<AgentTemplateEntity>>any()))
                .thenReturn(List.of(template));

        CompanionTemplateController controller = new CompanionTemplateController(templates);
        var item = controller.list().getData().get(0);

        assertEquals("template-a", item.id());
        assertEquals("zixuan-companion", item.code());
        assertEquals("治愈伙伴", item.name());
        assertEquals("friend", item.relationMode());
        assertEquals(List.of("laugh"), item.cues());
        assertEquals(5, item.getClass().getRecordComponents().length);
    }
}
