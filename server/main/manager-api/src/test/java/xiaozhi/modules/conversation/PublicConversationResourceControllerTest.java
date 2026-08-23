package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.conversation.controller.PublicConversationResourceController;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.service.TimbreService;

class PublicConversationResourceControllerTest {
    @Test
    void modelListContainsOnlyPublicFields() {
        ModelConfigService models = mock(ModelConfigService.class);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("model-a");
        model.setModelType("LLM");
        model.setModelCode("openai");
        model.setModelName("对话模型");
        model.setIsEnabled(1);
        when(models.getEnabledModelsByType("LLM")).thenReturn(List.of(model));
        PublicConversationResourceController controller = new PublicConversationResourceController(
                mock(AgentService.class), models, mock(TimbreService.class), mock(DeviceService.class));

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var result = controller.models("LLM").getData().get(0);
            assertEquals("model-a", result.get("id"));
            assertFalse(result.containsKey("configJson"));
            assertFalse(result.containsKey("api_key"));
        }
    }
}
