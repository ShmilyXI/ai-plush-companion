package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentSnapshotService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.companion.model.service.CompanionEffectiveModelService;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;
import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService.RuntimeTokenClaims;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;
import xiaozhi.modules.conversation.service.PublicConversationRuntimeBundleStore;
import xiaozhi.modules.conversation.service.PublicConversationQuotaService;
import xiaozhi.modules.conversation.service.PublicConversationCapabilityProjection;
import xiaozhi.modules.conversation.service.PublicConversationSkillProjectionService;
import xiaozhi.modules.conversation.service.impl.PublicConversationServiceImpl;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;

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
        assertEquals("companion:7:agent-a", bundle.config().get("memoryNamespace"));
        assertEquals("companion:7:agent-a", bundle.config().get("profileMemoryNamespace"));
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
                .thenReturn(Map.of(
                        "LLM", new CompanionRuntimeModel("model-a", Map.of()),
                        "ASR", new CompanionRuntimeModel("asr-a", Map.of()),
                        "TTS", new CompanionRuntimeModel("tts-a", Map.of())));
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");
        PublicConversationService service = new PublicConversationServiceImpl(agents, models, mock(TimbreService.class),
                tokens, params, null, store);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        var result = service.create(7L, request);

        ArgumentCaptor<xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO> captured =
                ArgumentCaptor.forClass(xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO.class);
        verify(store).put(captured.capture(), eq(java.time.Duration.ofMinutes(15)));
        assertEquals(result.conversationId(), captured.getValue().conversationId());
    }

    @Test
    void readsRuntimeBundleFromExternalStoreWhenConfigured() {
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
        ArgumentCaptor<xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO> captured =
                ArgumentCaptor.forClass(xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO.class);
        verify(store).put(captured.capture(), eq(java.time.Duration.ofMinutes(15)));
        when(store.get(result.conversationId())).thenReturn(captured.getValue());

        assertEquals(captured.getValue(), service.runtimeBundle(result.conversationId()));
        verify(store).get(result.conversationId());
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

    @Test
    void rejectsContinuationWhenThePersistedProfileVersionIsNoLongerActive() throws Exception {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(5);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);

        CompanionConversationIndexService index = mock(CompanionConversationIndexService.class);
        var conversation = new xiaozhi.modules.conversation.entity.CompanionConversationEntity();
        conversation.setId("conversation-a");
        conversation.setProfileId("agent-a");
        conversation.setProfileVersionNo(4);
        when(index.requireReadable(7L, "conversation-a")).thenReturn(conversation);

        PublicConversationServiceImpl service = new PublicConversationServiceImpl(
                agents, mock(CompanionEffectiveModelService.class), mock(TimbreService.class),
                mock(ConversationRuntimeTokenService.class), mock(SysParamsService.class));
        Field field = PublicConversationServiceImpl.class.getDeclaredField("conversationIndex");
        field.setAccessible(true);
        field.set(service, index);

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.continueConversation(7L, "conversation-a"));

        assertTrue(error.getMessage().contains("版本已更新"));
    }

    @Test
    void continuationNormalizesIncompleteTurnTextBeforeBuildingRuntimeBundle() throws Exception {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setAgentName("陪伴角色");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);

        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of(
                        "LLM", new CompanionRuntimeModel("model-a", Map.of()),
                        "ASR", new CompanionRuntimeModel("asr-a", Map.of()),
                        "TTS", new CompanionRuntimeModel("tts-a", Map.of())));
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");

        CompanionConversationEntity conversation = new CompanionConversationEntity();
        conversation.setId("conversation-a");
        conversation.setProfileId("agent-a");
        conversation.setProfileVersionNo(4);
        CompanionConversationTurnEntity incomplete = new CompanionConversationTurnEntity();
        incomplete.setUserText("还没有回复的问题");
        incomplete.setAssistantText(null);
        CompanionConversationIndexService index = mock(CompanionConversationIndexService.class);
        when(index.requireReadable(7L, "conversation-a")).thenReturn(conversation);
        when(index.history(7L, "conversation-a")).thenReturn(java.util.List.of(incomplete));

        PublicConversationServiceImpl service = new PublicConversationServiceImpl(
                agents, models, mock(TimbreService.class), tokens, params);
        Field field = PublicConversationServiceImpl.class.getDeclaredField("conversationIndex");
        field.setAccessible(true);
        field.set(service, index);

        PublicConversationSessionVO result = service.continueConversation(7L, "conversation-a");
        Map<?, ?> historyTurn = (Map<?, ?>) ((java.util.List<?>) service
                .runtimeBundle(result.conversationId()).config().get("history")).get(0);

        assertEquals("还没有回复的问题", historyTurn.get("content"));
        assertEquals("", historyTurn.get("reply"));
    }

    @Test
    void rejectsAudioSessionWhenRequiredRuntimeModelsAreMissing() {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(agent), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("llm-a", Map.of())));
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");
        request.setInputModes(Set.of("text", "audio"));
        request.setOutputModes(Set.of("text", "audio"));

        PublicConversationService service = new PublicConversationServiceImpl(
                agents, models, mock(TimbreService.class),
                mock(ConversationRuntimeTokenService.class), mock(SysParamsService.class));

        var error = assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));
        assertTrue(error.getMessage().contains("语音识别"));
    }

    @Test
    void rejectsProviderConfigKeysInPublicModelOverrides() {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(agent);
        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        PublicConversationAuthService auth = mock(PublicConversationAuthService.class);
        when(auth.current()).thenReturn(new PublicConversationAuthService.AuthenticatedCaller(
                7L, java.util.Set.of("conversation:text", "conversation:override"),
                java.util.Set.of("agent-a"), true, "key-a"));
        PublicConversationService service = new PublicConversationServiceImpl(
                agents, models, mock(TimbreService.class),
                mock(ConversationRuntimeTokenService.class), mock(SysParamsService.class), auth);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");
        request.setModelOverrides(Map.of("base_url", "http://attacker.invalid"));

        var error = assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));

        assertTrue(error.getMessage().contains("模型覆盖"));
        verifyNoInteractions(models);
    }

    @Test
    void createsRuntimeFromTheActiveImmutableAgentSnapshot() throws Exception {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO mutable = new AgentInfoVO();
        mutable.setId("agent-a");
        mutable.setAgentName("未发布草稿");
        mutable.setSystemPrompt("草稿提示词");
        mutable.setActiveVersionNo(4);
        when(agents.getAgentById("agent-a", 7L)).thenReturn(mutable);

        AgentInfoVO published = new AgentInfoVO();
        published.setId("agent-a");
        published.setAgentName("已发布角色");
        published.setSystemPrompt("已发布提示词");
        published.setActiveVersionNo(4);
        AgentSnapshotService snapshots = mock(AgentSnapshotService.class);
        when(snapshots.getPublishedAgent("agent-a", 7L, 4)).thenReturn(published);

        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(published), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("model-a", Map.of())));
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");

        PublicConversationServiceImpl service = new PublicConversationServiceImpl(
                agents, models, mock(TimbreService.class), tokens, params);
        Field field = PublicConversationServiceImpl.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        field.set(service, snapshots);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        var result = service.create(7L, request);

        assertEquals("已发布角色", result.publicMetadata().get("agent_name"));
        assertEquals("已发布提示词", service.runtimeBundle(result.conversationId()).config().get("systemPrompt"));
        verify(models).resolveRuntimeForPlayground(eq(7L), eq(published), any());
    }

    @Test
    void resolvesModelsFromThePublishedSnapshotInsteadOfMutableBindingDefaults() throws Exception {
        AgentService agents = mock(AgentService.class);
        AgentInfoVO mutable = new AgentInfoVO();
        mutable.setId("agent-a");
        mutable.setActiveVersionNo(4);
        mutable.setLlmModelId("model-draft");
        when(agents.getAgentById("agent-a", 7L)).thenReturn(mutable);

        AgentInfoVO published = new AgentInfoVO();
        published.setId("agent-a");
        published.setActiveVersionNo(4);
        published.setLlmModelId("model-published");
        AgentSnapshotService snapshots = mock(AgentSnapshotService.class);
        when(snapshots.getPublishedAgent("agent-a", 7L, 4)).thenReturn(published);

        CompanionEffectiveModelService models = mock(CompanionEffectiveModelService.class);
        when(models.resolveRuntimeForPlayground(eq(7L), eq(published), any()))
                .thenReturn(Map.of("LLM", new CompanionRuntimeModel("model-published", Map.of())));
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example");
        ConversationRuntimeTokenService tokens = mock(ConversationRuntimeTokenService.class);
        when(tokens.issue(any())).thenReturn("runtime-token");

        PublicConversationServiceImpl service = new PublicConversationServiceImpl(
                agents, models, mock(TimbreService.class), tokens, params);
        Field field = PublicConversationServiceImpl.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        field.set(service, snapshots);
        PublicConversationCreateDTO request = new PublicConversationCreateDTO();
        request.setAgentId("agent-a");

        service.create(7L, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> selected = ArgumentCaptor.forClass(Map.class);
        verify(models).resolveRuntimeForPlayground(eq(7L), eq(published), selected.capture());
        assertEquals("model-published", selected.getValue().get("LLM"));
    }
}
