package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.companion.model.service.CompanionEffectiveModelService;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;
import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService.RuntimeTokenClaims;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.impl.PublicConversationServiceImpl;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;

class PublicConversationServiceTest {
    @Test
    void createsSessionFromOwnedActiveAgentWithoutReturningRuntimeSecrets() {
        AgentService agents = mock(AgentService.class);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        TimbreService timbres = mock(TimbreService.class);
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        SysParamsService params = mock(SysParamsService.class);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("model-a", Map.of("api_key", "secret"))));
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        when(tokens.issue(any())).thenReturn("v1.token.signature");

        PublicConversationService service = new PublicConversationServiceImpl(agents, models, timbres, tokens, params);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");
        request.setInputModes(Set.of("text"));
        request.setOutputModes(Set.of("text"));

        PublicConversationSessionVO result = service.create(7L, request);

        assertEquals("agent-a", result.agentId());
        assertEquals(4, result.agentVersion());
        assertEquals("v1.token.signature", result.runtimeToken());
        assertEquals("ws://runtime.example/api/v1/conversations/" + result.conversationId() + "/stream", result.streamUrl());
        assertEquals("text", result.inputModes().iterator().next());
        verify(tokens).issue(any(RuntimeTokenClaims.class));
    }

    @Test
    void rejectsAgentWithoutActiveVersion() {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);

        PublicConversationService service = new PublicConversationServiceImpl(
                agents, mock(CompanionEffectiveModelService.class), mock(TimbreService.class),
                mock(ConversationRuntimeTokenService.class), mock(SysParamsService.class));
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));
    }
}
