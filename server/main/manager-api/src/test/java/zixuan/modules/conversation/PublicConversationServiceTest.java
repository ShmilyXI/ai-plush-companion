package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.common.constant.Constant;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.agent.vo.AgentInfoVO;
import zixuan.modules.companion.model.service.CompanionEffectiveModelService;
import zixuan.modules.companion.model.vo.CompanionRuntimeModel;
import zixuan.modules.conversation.dto.PublicConversationCreateDTO;
import zixuan.modules.conversation.service.ConversationRuntimeTokenService;
import zixuan.modules.conversation.service.ConversationRuntimeTokenService.RuntimeTokenClaims;
import zixuan.modules.conversation.service.PublicConversationService;
import zixuan.modules.conversation.service.PublicConversationRuntimeBundleStore;
import zixuan.modules.conversation.service.PublicConversationQuotaService;
import zixuan.modules.conversation.service.PublicConversationCapabilityProjection;
import zixuan.modules.conversation.service.PublicConversationSkillProjectionService;
import zixuan.modules.conversation.service.impl.PublicConversationServiceImpl;
import zixuan.modules.conversation.vo.PublicConversationSessionVO;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.timbre.vo.TimbreDetailsVO;

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

        var bundle = service.runtimeBundle(result.conversationId());
        assertEquals("agent-a", bundle.agentId());
        assertEquals(4, bundle.agentVersion());
        assertEquals("public:7:agent-a:" + result.conversationId(), bundle.config().get("memoryNamespace"));
        assertEquals("secret", ((Map<?, ?>) bundle.runtimeModels().get("LLM")).get("api_key"));
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

    @Test
    void persistsRuntimeBundleWithConversationTtl() {
        PublicConversationRuntimeBundleStore store = mock(PublicConversationRuntimeBundleStore.class);
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("model-a", Map.of())));
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");
        PublicConversationService service = new PublicConversationServiceImpl(agents, models, mock(TimbreService.class),
                tokens, params, null, store);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        var result = service.create(7L, request);

        ArgumentCaptor<zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO> captured =
                ArgumentCaptor.forClass(zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO.class);
        verify(store).put(captured.capture(), eq(java.time.Duration.ofMinutes(15)));
        assertEquals(result.conversationId(), captured.getValue().conversationId());
    }

    @Test
    void carriesRolePromptPersonalityModelAndVoiceIntoTheRuntimeBundle() {
        AgentService agents = mock(AgentService.class);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        TimbreService timbres = mock(TimbreService.class);
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        SysParamsService params = mock(SysParamsService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setAgentName("温柔角色");
        agent.setSystemPrompt("系统规则");
        agent.setPersonality("温柔、简洁");
        agent.setTtsModelId("tts-model-a");
        agent.setTtsVoiceId("voice-default");
        agent.setActiveVersionNo(9);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        TimbreDetailsVO voice = new TimbreDetailsVO();
        voice.setId("voice-b");
        voice.setTtsModelId("tts-model-a");
        when(timbres.get("voice-b")).thenReturn(voice);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of(
                        "LLM", new CompanionRuntimeModel("llm-a", Map.of("model_name", "model-a")),
                        "TTS", new CompanionRuntimeModel("tts-model-a", Map.of("voice", "voice-b"))));
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        when(tokens.issue(any())).thenReturn("runtime-token");
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");
        request.setVoiceId("voice-b");
        request.setInputModes(Set.of("text"));
        request.setOutputModes(Set.of("text", "audio"));

        PublicConversationService service = new PublicConversationServiceImpl(agents, models, timbres, tokens, params);
        PublicConversationSessionVO result = service.create(7L, request);
        var bundle = service.runtimeBundle(result.conversationId());

        assertEquals("voice-b", result.publicMetadata().get("tts_voice_id"));
        verify(tokens).issue(any(RuntimeTokenClaims.class));
        assertEquals("温柔角色", result.publicMetadata().get("agent_name"));
        assertEquals("系统规则", bundle.config().get("systemPrompt"));
        assertEquals("温柔、简洁", bundle.config().get("rolePrompt"));
        assertEquals("voice-b", ((Map<?, ?>) bundle.runtimeModels().get("TTS")).get("voice"));
    }

    @Test
    void carriesSanitizedSkillsAndToolsIntoRuntimeBundle() {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("llm-a", Map.of())));
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");
        PublicConversationSkillProjectionService projection = mock(PublicConversationSkillProjectionService.class);
        when(projection.project("agent-a", 4)).thenReturn(new PublicConversationCapabilityProjection(
                java.util.List.of(Map.of("id", "skill-weather", "toolNames", java.util.List.of("get_weather"))),
                Map.of("get_weather", Map.of("type", "PLUGIN", "refId", "plugin-weather"))));
        PublicConversationService service = new PublicConversationServiceImpl(agents, models,
                mock(TimbreService.class), tokens, params, null, null, projection);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        var created = service.create(7L, request);
        var config = service.runtimeBundle(created.conversationId()).config();

        assertEquals("skill-weather", ((Map<?, ?>) ((java.util.List<?>) config.get("skills")).get(0)).get("id"));
        assertTrue(((Map<?, ?>) config.get("tools")).containsKey("get_weather"));
        assertTrue(!config.toString().contains("secret"));
    }

    @Test
    void appliesSessionQuotaAfterAgentAndVoiceValidation() {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("model-a", Map.of())));
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");
        PublicConversationQuotaService quota = mock(PublicConversationQuotaService.class);
        PublicConversationService service = new PublicConversationServiceImpl(agents, models, mock(TimbreService.class),
                tokens, params, null, null, null, quota);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        service.create(7L, request);

        verify(quota).requireSession(7L, null);
    }
}
