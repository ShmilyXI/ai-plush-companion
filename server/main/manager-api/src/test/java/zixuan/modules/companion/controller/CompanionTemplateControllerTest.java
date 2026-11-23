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
        when(templates.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<AgentTemplateEntity>>any()))
                .thenReturn(List.of(template));

        CompanionTemplateController controller = new CompanionTemplateController(templates);
        var item = controller.list().getData().get(0);

        assertEquals("template-a", item.id());
        assertEquals("zixuan-companion", item.code());
        assertEquals("治愈伙伴", item.name());
        assertEquals("friend", item.relationMode());
        // 介绍是给用户选角色时看的一句话，来自预设角色的定义，不是提示词。
        assertEquals("温柔的倾听者，累的时候来坐一会儿", item.description());
        assertEquals(5, item.getClass().getRecordComponents().length);
    }

    @Test
    void girlfriendPresetIsListedAsLoverMode() {
        AgentTemplateService templates = mock(AgentTemplateService.class);
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-sunian");
        template.setAgentCode("sunian-companion");
        template.setAgentName("苏念");
        when(templates.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<AgentTemplateEntity>>any()))
                .thenReturn(List.of(template));

        var item = new CompanionTemplateController(templates).list().getData().get(0);

        assertEquals("lover", item.relationMode());
        assertEquals("爱脸红的女朋友，偷偷把你们的日常写进小说", item.description());
    }
}
