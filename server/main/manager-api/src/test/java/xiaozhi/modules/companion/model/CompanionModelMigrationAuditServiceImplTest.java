package xiaozhi.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.entity.CompanionProfileModelEntity;
import xiaozhi.modules.companion.model.service.impl.CompanionModelMigrationAuditServiceImpl;
import xiaozhi.modules.companion.model.vo.CompanionModelMigrationAuditReportVO;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.timbre.dao.TimbreDao;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.voiceclone.dao.VoiceCloneDao;
import xiaozhi.modules.voiceclone.entity.VoiceCloneEntity;

class CompanionModelMigrationAuditServiceImplTest {
    private final AgentDao agentDao = mock(AgentDao.class);
    private final CompanionProfileModelDao profileModelDao = mock(CompanionProfileModelDao.class);
    private final ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
    private final TimbreDao timbreDao = mock(TimbreDao.class);
    private final VoiceCloneDao voiceCloneDao = mock(VoiceCloneDao.class);
    private final CompanionModelMigrationAuditServiceImpl service = new CompanionModelMigrationAuditServiceImpl(
            agentDao, profileModelDao, modelConfigDao, timbreDao, voiceCloneDao);

    @Test
    void auditsGlobalDefaultMissingAndPrivateWithoutMutation() {
        AgentEntity readyGlobal = agent("agent-ready-global");
        readyGlobal.setTtsModelId("tts-global");
        readyGlobal.setTtsVoiceId("voice-global");
        AgentEntity readyDefault = agent("agent-ready-default");
        readyDefault.setTtsVoiceId("voice-default");
        AgentEntity privateAgent = agent("agent-private");
        privateAgent.setTtsVoiceId("voice-default");
        AgentEntity missingAgent = agent("agent-missing");
        missingAgent.setLlmModelId("missing-llm");
        missingAgent.setTtsVoiceId("voice-default");
        stubAgents(List.of(privateAgent, readyDefault, missingAgent, readyGlobal));
        stubBindings(List.of(
                binding("agent-ready-global", "TTS", "global", "tts-global"),
                binding("agent-ready-default", "TTS", "default", null),
                binding("agent-private", "LLM", "private", "private-1"),
                binding("agent-missing", "LLM", "global", "missing-llm")));
        stubModels(List.of(
                model("llm-default", "LLM", 1, 1),
                model("asr-default", "ASR", 1, 1),
                model("tts-global", "TTS", 1, 0),
                model("tts-default", "TTS", 1, 1),
                model("vad-default", "VAD", 1, 1),
                model("vllm-default", "VLLM", 1, 1),
                model("memory-default", "Memory", 1, 1)));
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-global", "tts-global"),
                voice("voice-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(4, report.getTotalProfiles());
        assertEquals(2, report.getReadyProfiles());
        assertEquals(1, report.getPrivateBindingProfiles());
        assertEquals(1, report.getMissingNativeModelProfiles());
        assertEquals(0, report.getInvalidTtsPairProfiles());
        assertEquals(List.of("agent-ready-default", "agent-ready-global"), report.getReadyAgentIds());
        assertEquals(List.of("agent-private"), report.getPrivateBindingAgentIds());
        assertEquals(List.of("agent-missing"), report.getMissingNativeModelAgentIds());
        assertEquals(List.of("agent-missing", "agent-private"), report.getNeedsSelectionAgentIds());
        assertEquals(2, report.getNeedsSelectionProfiles());
        verifyReadOnly();
    }

    @Test
    void wrongTypeDisabledMissingDefaultAndMismatchedVoiceNeedSelectionWithSortedUniqueIds() {
        AgentEntity wrongType = agent("z-wrong-type");
        wrongType.setLlmModelId("tts-in-llm-slot");
        wrongType.setVadModelId("vad-valid");
        wrongType.setTtsVoiceId("voice-default");
        AgentEntity disabled = agent("a-disabled");
        disabled.setAsrModelId("asr-disabled");
        disabled.setVadModelId("vad-valid");
        disabled.setTtsVoiceId("voice-default");
        AgentEntity missingDefault = agent("m-default-missing");
        missingDefault.setTtsVoiceId("voice-default");
        AgentEntity invalidVoice = agent("b-invalid-voice");
        invalidVoice.setTtsModelId("tts-selected");
        invalidVoice.setTtsVoiceId("voice-other-model");
        invalidVoice.setVadModelId("vad-valid");
        stubAgents(List.of(wrongType, invalidVoice, disabled, missingDefault));
        stubBindings(List.of(
                binding("m-default-missing", "VAD", "default", null),
                binding("z-wrong-type", "LLM", "global", "tts-in-llm-slot"),
                binding("z-wrong-type", "LLM", "global", "tts-in-llm-slot")));
        stubModels(List.of(
                model("llm-default", "LLM", 1, 1),
                model("tts-in-llm-slot", "TTS", 1, 0),
                model("asr-default", "ASR", 1, 1),
                model("asr-disabled", "ASR", 0, 0),
                model("vad-disabled-default", "VAD", 0, 1),
                model("vad-valid", "VAD", 1, 0),
                model("tts-selected", "TTS", 1, 0),
                model("tts-other", "TTS", 1, 0),
                model("tts-default", "TTS", 1, 1),
                model("vllm-default", "VLLM", 1, 1),
                model("memory-default", "Memory", 1, 1)));
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-other-model", "tts-other"),
                voice("voice-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(0, report.getReadyProfiles());
        assertEquals(List.of("a-disabled", "m-default-missing", "z-wrong-type"),
                report.getMissingNativeModelAgentIds());
        assertEquals(List.of("b-invalid-voice"), report.getInvalidTtsPairAgentIds());
        assertEquals(List.of("a-disabled", "b-invalid-voice", "m-default-missing", "z-wrong-type"),
                report.getNeedsSelectionAgentIds());
        assertEquals(4, report.getNeedsSelectionProfiles());
        verifyReadOnly();
    }

    @Test
    void blankRuntimeWithoutBindingDoesNotFallbackToEnabledDefault() {
        AgentEntity agent = agent("agent-no-vad-default");
        agent.setVadModelId(null);
        agent.setTtsVoiceId("voice-default");
        stubAgents(List.of(agent));
        stubBindings(List.of());
        stubModels(List.of(
                model("llm-default", "LLM", 1, 1),
                model("asr-default", "ASR", 1, 1),
                model("tts-default", "TTS", 1, 1),
                model("vad-default", "VAD", 1, 1),
                model("vllm-default", "VLLM", 1, 1),
                model("memory-default", "Memory", 1, 1)));
        when(timbreDao.selectList(any())).thenReturn(List.of(voice("voice-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-no-vad-default"), report.getMissingNativeModelAgentIds());
        assertEquals(List.of("agent-no-vad-default"), report.getNeedsSelectionAgentIds());
        assertEquals(0, report.getReadyProfiles());
        verifyReadOnly();
    }

    @Test
    void explicitGlobalBindingCannotOverrideInvalidRuntimeModelForTheSameType() {
        AgentEntity agent = agent("agent-global-overrides-legacy");
        agent.setLlmModelId("disabled-legacy-llm");
        agent.setTtsVoiceId("voice-default");
        stubAgents(List.of(agent));
        stubBindings(List.of(
                binding(agent.getId(), "LLM", "global", "llm-global")));
        stubModels(List.of(
                model("llm-global", "LLM", 1, 0),
                model("disabled-legacy-llm", "LLM", 0, 0),
                model("asr-default", "ASR", 1, 1),
                model("tts-default", "TTS", 1, 1),
                model("vad-default", "VAD", 1, 1),
                model("vllm-default", "VLLM", 1, 1),
                model("memory-default", "Memory", 1, 1)));
        when(timbreDao.selectList(any())).thenReturn(List.of(voice("voice-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-global-overrides-legacy"), report.getMissingNativeModelAgentIds());
        assertEquals(List.of(), report.getReadyAgentIds());
        assertEquals(0, report.getReadyProfiles());
        verifyReadOnly();
    }

    @Test
    void nonTtsBindingsMustMatchEachRuntimeModelField() {
        AgentEntity llm = runtimeAgent("drift-llm", "tts-default");
        llm.setLlmModelId("llm-other");
        AgentEntity asr = runtimeAgent("drift-asr", "tts-default");
        asr.setAsrModelId("asr-other");
        AgentEntity vad = runtimeAgent("drift-vad", "tts-default");
        vad.setVadModelId("vad-other");
        AgentEntity vllm = runtimeAgent("drift-vllm", "tts-default");
        vllm.setVllmModelId("vllm-other");
        AgentEntity memory = runtimeAgent("drift-memory", "tts-default");
        memory.setMemModelId("memory-other");
        stubAgents(List.of(llm, asr, vad, vllm, memory));
        stubBindings(List.of(
                binding(llm.getId(), "LLM", "global", "llm-default"),
                binding(asr.getId(), "ASR", "global", "asr-default"),
                binding(vad.getId(), "VAD", "default", null),
                binding(vllm.getId(), "VLLM", "global", "vllm-default"),
                binding(memory.getId(), "Memory", "default", null)));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.addAll(List.of(
                model("llm-other", "LLM", 1, 0),
                model("asr-other", "ASR", 1, 0),
                model("vad-other", "VAD", 1, 0),
                model("vllm-other", "VLLM", 1, 0),
                model("memory-other", "Memory", 1, 0)));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of(), report.getReadyAgentIds());
        assertEquals(List.of("drift-asr", "drift-llm", "drift-memory", "drift-vad", "drift-vllm"),
                report.getMissingNativeModelAgentIds());
        assertEquals(report.getMissingNativeModelAgentIds(), report.getNeedsSelectionAgentIds());
    }

    @Test
    void emptyVoiceIsValidWhenResolvedTtsModelHasNoVoices() {
        AgentEntity agent = agent("agent-tts-without-voices");
        stubAgents(List.of(agent));
        stubBindings(List.of());
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-tts-without-voices"), report.getReadyAgentIds());
        assertEquals(List.of(), report.getInvalidTtsPairAgentIds());
        verifyNoInteractions(voiceCloneDao);
        verifyReadOnly();
    }

    @Test
    void emptyVoiceIsInvalidWhenResolvedTtsModelHasVoices() {
        AgentEntity agent = agent("agent-tts-requires-voice");
        stubAgents(List.of(agent));
        stubBindings(List.of());
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of(voice("voice-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-tts-requires-voice"), report.getInvalidTtsPairAgentIds());
        assertEquals(List.of("agent-tts-requires-voice"), report.getNeedsSelectionAgentIds());
        verifyReadOnly();
    }

    @Test
    void multipleEnabledDefaultsForOneTypeAreAmbiguousAndNeedSelection() {
        AgentEntity agent = agent("agent-ambiguous-default");
        stubAgents(List.of(agent));
        stubBindings(List.of(binding(agent.getId(), "VAD", "default", null)));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("vad-default-2", "VAD", 1, 1));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-ambiguous-default"), report.getMissingNativeModelAgentIds());
        assertEquals(List.of("agent-ambiguous-default"), report.getNeedsSelectionAgentIds());
        verifyReadOnly();
    }

    @Test
    void duplicateBindingsAndUnknownBindingTypesAreMigrationAnomalies() {
        AgentEntity duplicate = agent("agent-duplicate-binding");
        AgentEntity unknown = agent("agent-unknown-binding-type");
        stubAgents(List.of(unknown, duplicate));
        stubBindings(List.of(
                binding(duplicate.getId(), "LLM", "global", "llm-default"),
                binding(duplicate.getId(), "LLM", "global", "llm-default"),
                binding(unknown.getId(), "Unknown", "global", "llm-default")));
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-duplicate-binding", "agent-unknown-binding-type"),
                report.getMissingNativeModelAgentIds());
        assertEquals(List.of("agent-duplicate-binding", "agent-unknown-binding-type"),
                report.getNeedsSelectionAgentIds());
        verifyReadOnly();
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void auditIsReadOnlyAndReadsAgentsThroughPagination() throws Exception {
        Transactional transactional = CompanionModelMigrationAuditServiceImpl.class
                .getMethod("audit").getAnnotation(Transactional.class);
        assertEquals(true, transactional.readOnly());
        Page<AgentEntity> page = new Page<>(1, 500, false);
        page.setRecords(List.of(agent("agent-paged")));
        when(agentDao.selectPage(any(), any())).thenReturn(page);
        when(profileModelDao.selectList(any())).thenReturn(List.of());
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("agent-paged"), report.getReadyAgentIds());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> agentQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> bindingQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> modelQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> timbreQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(agentDao).selectPage(any(), agentQuery.capture());
        verify(profileModelDao).selectList(bindingQuery.capture());
        verify(modelConfigDao).selectList(modelQuery.capture());
        verify(timbreDao).selectList(timbreQuery.capture());
        assertEquals("id,user_id,companion_enabled,llm_model_id,asr_model_id,tts_model_id,vad_model_id,vllm_model_id,mem_model_id,tts_voice_id",
                agentQuery.getValue().getSqlSelect());
        assertTrue(agentQuery.getValue().getSqlSegment().contains("companion_enabled"));
        assertEquals(java.util.Set.of(1),
                java.util.Set.copyOf(agentQuery.getValue().getParamNameValuePairs().values()));
        assertEquals("agent_id,model_type,source_type,resource_id", bindingQuery.getValue().getSqlSelect());
        assertEquals("id,model_type,is_default,is_enabled", modelQuery.getValue().getSqlSelect());
        assertEquals("id,tts_model_id", timbreQuery.getValue().getSqlSelect());
        verify(agentDao, never()).selectList(any());
    }

    @Test
    void emptyAgentTableReturnsEmptyReportWithoutLoadingOtherTables() {
        stubAgents(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(0, report.getTotalProfiles());
        assertEquals(List.of(), report.getNeedsSelectionAgentIds());
        verifyNoInteractions(profileModelDao, modelConfigDao, timbreDao, voiceCloneDao);
    }

    @Test
    void agentAuditContinuesAcrossPages() {
        List<AgentEntity> agents = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> agent("agent-%03d".formatted(index))).toList();
        when(agentDao.selectPage(any(), any())).thenAnswer(invocation -> {
            IPage<AgentEntity> requested = invocation.getArgument(0);
            int from = (int) ((requested.getCurrent() - 1) * requested.getSize());
            int to = Math.min(from + (int) requested.getSize(), agents.size());
            requested.setRecords(from >= agents.size() ? List.of() : agents.subList(from, to));
            return requested;
        });
        stubBindings(List.of());
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(501, report.getTotalProfiles());
        assertEquals(501, report.getReadyProfiles());
        verify(agentDao, times(2)).selectPage(any(), any());
        verify(profileModelDao, times(2)).selectList(any());
        verifyReadOnly();
    }

    @Test
    void ordinaryAgentsAreExcludedFromEveryAuditCount() {
        AgentEntity companion = agent("companion-agent");
        AgentEntity ordinary = agent("ordinary-agent");
        ordinary.setCompanionEnabled(0);
        ordinary.setLlmModelId("missing-llm");
        stubAgents(List.of(companion, ordinary));
        stubBindings(List.of());
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of());

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(1, report.getTotalProfiles());
        assertEquals(List.of("companion-agent"), report.getReadyAgentIds());
        assertEquals(List.of(), report.getNeedsSelectionAgentIds());
    }

    @Test
    void trainedOwnedCloneMatchingResolvedTtsModelIsValid() {
        AgentEntity companion = agent("companion-clone");
        companion.setUserId(7L);
        companion.setTtsModelId("tts-default");
        companion.setTtsVoiceId("voice-clone");
        stubAgents(List.of(companion));
        stubBindings(List.of(binding(companion.getId(), "TTS", "global", "tts-default")));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("tts-other", "TTS", 1, 0));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of());
        when(voiceCloneDao.selectList(any())).thenReturn(List.of(
                clone("voice-clone", 7L, "tts-default", 2)));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("companion-clone"), report.getReadyAgentIds());
        assertEquals(List.of(), report.getInvalidTtsPairAgentIds());
    }

    @Test
    void bindingAndCloneMatchCannotHideDifferentRuntimeTtsModel() {
        AgentEntity companion = runtimeAgent("companion-clone-drift", "tts-other");
        companion.setUserId(7L);
        companion.setTtsVoiceId("voice-clone-default");
        stubAgents(List.of(companion));
        stubBindings(List.of(binding(companion.getId(), "TTS", "global", "tts-default")));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("tts-other", "TTS", 1, 0));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of());
        when(voiceCloneDao.selectList(any())).thenReturn(List.of(
                clone("voice-clone-default", 7L, "tts-default", 2)));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of(), report.getReadyAgentIds());
        assertEquals(List.of("companion-clone-drift"), report.getMissingNativeModelAgentIds());
        assertEquals(List.of("companion-clone-drift"), report.getInvalidTtsPairAgentIds());
    }

    @Test
    void matchingRuntimeAndBindingsAcceptCloneAndNativeVoice() {
        AgentEntity globalClone = runtimeAgent("companion-global-clone", "tts-default");
        globalClone.setUserId(7L);
        globalClone.setTtsVoiceId("voice-clone-default");
        AgentEntity defaultNative = runtimeAgent("companion-default-native", "tts-default");
        defaultNative.setUserId(7L);
        defaultNative.setTtsVoiceId("voice-native-default");
        stubAgents(List.of(globalClone, defaultNative));
        stubBindings(List.of(
                binding(globalClone.getId(), "TTS", "global", "tts-default"),
                binding(defaultNative.getId(), "TTS", "default", null)));
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-native-default", "tts-default")));
        when(voiceCloneDao.selectList(any())).thenReturn(List.of(
                clone("voice-clone-default", 7L, "tts-default", 2)));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("companion-default-native", "companion-global-clone"), report.getReadyAgentIds());
        assertEquals(List.of(), report.getMissingNativeModelAgentIds());
        assertEquals(List.of(), report.getInvalidTtsPairAgentIds());
    }

    @Test
    void nullRuntimeTtsIsMissingEvenWithGlobalOrDefaultBindingAndNativeVoice() {
        AgentEntity global = runtimeAgent("companion-null-global", null);
        global.setTtsVoiceId("voice-native-default");
        AgentEntity defaults = runtimeAgent("companion-null-default", null);
        defaults.setTtsVoiceId("voice-native-default");
        stubAgents(List.of(global, defaults));
        stubBindings(List.of(
                binding(global.getId(), "TTS", "global", "tts-default"),
                binding(defaults.getId(), "TTS", "default", null)));
        stubModels(defaultModels());
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-native-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of(), report.getReadyAgentIds());
        assertEquals(List.of("companion-null-default", "companion-null-global"),
                report.getMissingNativeModelAgentIds());
    }

    @Test
    void nativeVoiceCannotHideBindingAndRuntimeTtsDrift() {
        AgentEntity voiceMatchesBinding = runtimeAgent("companion-native-binding-voice", "tts-other");
        voiceMatchesBinding.setTtsVoiceId("voice-native-default");
        AgentEntity voiceMatchesRuntime = runtimeAgent("companion-native-runtime-voice", "tts-other");
        voiceMatchesRuntime.setTtsVoiceId("voice-native-other");
        stubAgents(List.of(voiceMatchesBinding, voiceMatchesRuntime));
        stubBindings(List.of(
                binding(voiceMatchesBinding.getId(), "TTS", "global", "tts-default"),
                binding(voiceMatchesRuntime.getId(), "TTS", "global", "tts-default")));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("tts-other", "TTS", 1, 0));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-native-default", "tts-default"),
                voice("voice-native-other", "tts-other")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of(), report.getReadyAgentIds());
        assertEquals(List.of("companion-native-binding-voice", "companion-native-runtime-voice"),
                report.getMissingNativeModelAgentIds());
        assertEquals(List.of("companion-native-binding-voice"), report.getInvalidTtsPairAgentIds());
    }

    @Test
    void defaultTtsBindingWithDifferentValidRuntimeIsMissingWhileVoiceUsesRuntime() {
        AgentEntity companion = runtimeAgent("companion-default-drift", "tts-other");
        companion.setTtsVoiceId("voice-native-other");
        stubAgents(List.of(companion));
        stubBindings(List.of(binding(companion.getId(), "TTS", "default", null)));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("tts-other", "TTS", 1, 0));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-native-other", "tts-other")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("companion-default-drift"), report.getMissingNativeModelAgentIds());
        assertEquals(List.of(), report.getInvalidTtsPairAgentIds());
        assertEquals(List.of("companion-default-drift"), report.getNeedsSelectionAgentIds());
    }

    @Test
    void invalidRuntimeTtsModelsAreMissingWithoutSpeculativeVoiceErrors() {
        AgentEntity disabled = runtimeAgent("companion-disabled-tts", "tts-disabled");
        disabled.setTtsVoiceId("voice-native-default");
        AgentEntity wrongType = runtimeAgent("companion-wrong-type-tts", "llm-default");
        wrongType.setTtsVoiceId("voice-native-default");
        stubAgents(List.of(disabled, wrongType));
        stubBindings(List.of());
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("tts-disabled", "TTS", 0, 0));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-native-default", "tts-default")));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(List.of("companion-disabled-tts", "companion-wrong-type-tts"),
                report.getMissingNativeModelAgentIds());
        assertEquals(List.of(), report.getInvalidTtsPairAgentIds());
        assertEquals(report.getMissingNativeModelAgentIds(), report.getNeedsSelectionAgentIds());
    }

    @Test
    void auditsOnlyCompanionProfilesAndValidatesNativeAndCloneVoicesWithNativePriority() {
        AgentEntity nativeVoice = agent("agent-native");
        nativeVoice.setUserId(7L);
        nativeVoice.setTtsVoiceId("voice-native");
        AgentEntity cloneVoice = agent("agent-clone-valid");
        cloneVoice.setUserId(7L);
        cloneVoice.setTtsModelId("tts-default");
        cloneVoice.setTtsVoiceId("voice-clone-valid");
        AgentEntity duplicateCloneVoice = agent("agent-clone-valid-duplicate");
        duplicateCloneVoice.setUserId(7L);
        duplicateCloneVoice.setTtsVoiceId("voice-clone-valid");
        AgentEntity otherUsersClone = agent("agent-clone-other-user");
        otherUsersClone.setUserId(7L);
        otherUsersClone.setTtsVoiceId("voice-clone-other-user");
        AgentEntity untrainedClone = agent("agent-clone-untrained");
        untrainedClone.setUserId(7L);
        untrainedClone.setTtsVoiceId("voice-clone-untrained");
        AgentEntity crossModelClone = agent("agent-clone-cross-model");
        crossModelClone.setUserId(7L);
        crossModelClone.setTtsVoiceId("voice-clone-cross-model");
        AgentEntity nullOwnerClone = agent("agent-clone-null-owner");
        nullOwnerClone.setTtsVoiceId("voice-clone-null-owner");
        AgentEntity collidingVoiceId = agent("agent-colliding-voice");
        collidingVoiceId.setUserId(7L);
        collidingVoiceId.setTtsVoiceId("voice-collision");
        AgentEntity ordinaryAgent = agent("ordinary-agent");
        ordinaryAgent.setCompanionEnabled(0);
        ordinaryAgent.setUserId(7L);
        ordinaryAgent.setTtsVoiceId("missing-voice");
        stubAgents(List.of(nativeVoice, cloneVoice, duplicateCloneVoice, otherUsersClone, untrainedClone,
                crossModelClone, nullOwnerClone, collidingVoiceId, ordinaryAgent));
        stubBindings(List.of(binding(cloneVoice.getId(), "TTS", "global", "tts-default")));
        List<ModelConfigEntity> models = new java.util.ArrayList<>(defaultModels());
        models.add(model("tts-other", "TTS", 1, 0));
        stubModels(models);
        when(timbreDao.selectList(any())).thenReturn(List.of(
                voice("voice-native", "tts-default"),
                voice("voice-collision", "tts-other")));
        when(voiceCloneDao.selectList(any())).thenReturn(List.of(
                clone("voice-clone-valid", 7L, "tts-default", 2),
                clone("voice-clone-other-user", 8L, "tts-default", 2),
                clone("voice-clone-untrained", 7L, "tts-default", 1),
                clone("voice-clone-cross-model", 7L, "tts-other", 2),
                clone("voice-clone-null-owner", null, "tts-default", 2),
                clone("voice-collision", 7L, "tts-default", 2)));

        CompanionModelMigrationAuditReportVO report = service.audit();

        assertEquals(8, report.getTotalProfiles());
        assertEquals(List.of("agent-clone-valid", "agent-clone-valid-duplicate", "agent-native"),
                report.getReadyAgentIds());
        assertEquals(List.of("agent-clone-cross-model", "agent-clone-null-owner", "agent-clone-other-user",
                "agent-clone-untrained", "agent-colliding-voice"), report.getInvalidTtsPairAgentIds());
        assertEquals(report.getInvalidTtsPairAgentIds(), report.getNeedsSelectionAgentIds());
        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<QueryWrapper<VoiceCloneEntity>> cloneQuery = (ArgumentCaptor) ArgumentCaptor.forClass(
                QueryWrapper.class);
        verify(voiceCloneDao).selectList(cloneQuery.capture());
        assertEquals("id,model_id,user_id,train_status", cloneQuery.getValue().getSqlSelect());
        assertTrue(cloneQuery.getValue().getSqlSegment().contains("id IN"));
        java.util.Set<String> queriedCloneIds = cloneQuery.getValue().getParamNameValuePairs().values().stream()
                .map(String.class::cast)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("voice-clone-valid", "voice-clone-other-user",
                        "voice-clone-untrained", "voice-clone-cross-model", "voice-clone-null-owner"),
                queriedCloneIds);
        verifyReadOnly();
    }

    private void verifyReadOnly() {
        verify(profileModelDao, never()).deleteByAgentId(anyString());
        verify(profileModelDao, never()).insert(any(CompanionProfileModelEntity.class));
        verify(profileModelDao, never()).updateById(any(CompanionProfileModelEntity.class));
        verify(agentDao, never()).insert(any(AgentEntity.class));
        verify(agentDao, never()).updateById(any(AgentEntity.class));
        verify(agentDao, never()).deleteById(anyString());
        verify(modelConfigDao, never()).insert(any(ModelConfigEntity.class));
        verify(modelConfigDao, never()).updateById(any(ModelConfigEntity.class));
        verify(modelConfigDao, never()).deleteById(anyString());
        verify(timbreDao, never()).insert(any(TimbreEntity.class));
        verify(timbreDao, never()).updateById(any(TimbreEntity.class));
        verify(timbreDao, never()).deleteById(anyString());
        verify(voiceCloneDao, never()).insert(any(VoiceCloneEntity.class));
        verify(voiceCloneDao, never()).updateById(any(VoiceCloneEntity.class));
        verify(voiceCloneDao, never()).deleteById(anyString());
        assertNoMutations(agentDao);
        assertNoMutations(profileModelDao);
        assertNoMutations(modelConfigDao);
        assertNoMutations(timbreDao);
        assertNoMutations(voiceCloneDao);
    }

    private void assertNoMutations(Object dao) {
        List<String> mutations = org.mockito.Mockito.mockingDetails(dao).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(name -> name.startsWith("insert") || name.startsWith("update") || name.startsWith("delete"))
                .toList();
        assertEquals(List.of(), mutations);
    }

    private void stubModels(List<ModelConfigEntity> models) {
        when(modelConfigDao.selectList(any())).thenReturn(models);
    }

    private void stubAgents(List<AgentEntity> agents) {
        when(agentDao.selectPage(any(), any())).thenAnswer(invocation -> {
            IPage<AgentEntity> requested = invocation.getArgument(0);
            requested.setRecords(agents);
            return requested;
        });
    }

    private void stubBindings(List<CompanionProfileModelEntity> bindings) {
        when(profileModelDao.selectList(any())).thenReturn(bindings);
    }

    private List<ModelConfigEntity> defaultModels() {
        return List.of(
                model("llm-default", "LLM", 1, 1),
                model("asr-default", "ASR", 1, 1),
                model("tts-default", "TTS", 1, 1),
                model("vad-default", "VAD", 1, 1),
                model("vllm-default", "VLLM", 1, 1),
                model("memory-default", "Memory", 1, 1));
    }

    private AgentEntity agent(String id) {
        AgentEntity entity = new AgentEntity();
        entity.setId(id);
        entity.setCompanionEnabled(1);
        entity.setLlmModelId("llm-default");
        entity.setAsrModelId("asr-default");
        entity.setTtsModelId("tts-default");
        entity.setVadModelId("vad-default");
        entity.setVllmModelId("vllm-default");
        entity.setMemModelId("memory-default");
        return entity;
    }

    private AgentEntity runtimeAgent(String id, String ttsModelId) {
        AgentEntity entity = agent(id);
        entity.setTtsModelId(ttsModelId);
        return entity;
    }

    private CompanionProfileModelEntity binding(String agentId, String type, String source, String resourceId) {
        CompanionProfileModelEntity entity = new CompanionProfileModelEntity();
        entity.setAgentId(agentId);
        entity.setModelType(type);
        entity.setSourceType(source);
        entity.setResourceId(resourceId);
        return entity;
    }

    private ModelConfigEntity model(String id, String type, int enabled, int isDefault) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(id);
        entity.setModelType(type);
        entity.setIsEnabled(enabled);
        entity.setIsDefault(isDefault);
        return entity;
    }

    private TimbreEntity voice(String id, String ttsModelId) {
        TimbreEntity entity = new TimbreEntity();
        entity.setId(id);
        entity.setTtsModelId(ttsModelId);
        return entity;
    }

    private VoiceCloneEntity clone(String id, Long userId, String modelId, Integer trainStatus) {
        VoiceCloneEntity entity = new VoiceCloneEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setModelId(modelId);
        entity.setTrainStatus(trainStatus);
        return entity;
    }
}
