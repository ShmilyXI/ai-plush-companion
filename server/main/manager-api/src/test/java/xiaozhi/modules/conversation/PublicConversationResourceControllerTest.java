package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.dto.AgentDTO;
import xiaozhi.modules.conversation.controller.PublicConversationResourceController;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.vo.UserShowDeviceListVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.service.TimbreService;

class PublicConversationResourceControllerTest {
    @Test
    void agentListIncludesSafeVoiceSelectionMetadata() {
        AgentService agents = mock(AgentService.class);
        AgentDTO agent = new AgentDTO();
        agent.setId("agent-a");
        agent.setAgentName("小夏");
        agent.setTtsModelId("tts-a");
        agent.setTtsVoiceId("voice-a");
        agent.setTtsModelName("TTS");
        agent.setTtsVoiceName("女声");
        when(agents.getUserAgents(7L, "", "name")).thenReturn(List.of(agent));
        PublicConversationResourceController controller = new PublicConversationResourceController(
                agents, mock(ModelConfigService.class), mock(TimbreService.class), mock(DeviceService.class));

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var result = controller.agents("").getData().get(0);
            assertEquals("tts-a", result.getTtsModelId());
            assertEquals("voice-a", result.getTtsVoiceId());
        }
    }

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

    @Test
    void apiKeyDeviceListIsLimitedToItsAgentAllowlist() {
        DeviceService devices = mock(DeviceService.class);
        UserShowDeviceListVO allowed = new UserShowDeviceListVO();
        allowed.setId("device-a");
        UserShowDeviceListVO denied = new UserShowDeviceListVO();
        denied.setId("device-b");
        when(devices.getUserDeviceList(7L, "agent-a")).thenReturn(List.of(allowed));
        PublicConversationAuthService auth = mock(PublicConversationAuthService.class);
        when(auth.current()).thenReturn(new PublicConversationAuthService.AuthenticatedCaller(
                7L, Set.of("resource:read"), Set.of("agent-a"), true, "key-a"));
        PublicConversationResourceController controller = new PublicConversationResourceController(
                mock(AgentService.class), mock(ModelConfigService.class), mock(TimbreService.class), devices, auth);

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var result = controller.devices().getData();
            assertEquals(List.of(allowed), result);
        }
        org.mockito.Mockito.verify(devices).getUserDeviceList(7L, "agent-a");
        org.mockito.Mockito.verify(devices, org.mockito.Mockito.never()).getUserDeviceList(7L, null);
    }
}
