package xiaozhi.modules.companion.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.ArgumentMatchers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentSnapshotService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.companion.dto.CompanionProfileSaveDTO;
import xiaozhi.modules.companion.model.dao.CompanionProfileModelDao;
import xiaozhi.modules.companion.model.dto.CompanionProfileModelSaveDTO;
import xiaozhi.modules.companion.model.entity.CompanionProfileModelEntity;
import xiaozhi.modules.companion.model.service.CompanionEffectiveModelService;
import xiaozhi.modules.companion.model.service.CompanionPrivateModelService;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;
import xiaozhi.modules.companion.model.vo.CompanionProfileModelVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.companion.service.CompanionProfileService;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.companion.vo.CompanionProfileVO;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.voiceclone.entity.VoiceCloneEntity;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

class CompanionProfileServiceImplTest {
    private final AgentDao agentDao = mock(AgentDao.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AgentTemplateService templateService = mock(AgentTemplateService.class);
    private final AgentSnapshotService snapshotService = mock(AgentSnapshotService.class);
    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final TimbreService timbreService = mock(TimbreService.class);
    private final VoiceCloneService voiceCloneService = mock(VoiceCloneService.class);
    private final CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
    private final SysUserDao sysUserDao = userDao();
    private final CompanionProfileModelDao profileModelDao = mock(CompanionProfileModelDao.class);
    private final CompanionEffectiveModelService effectiveModels = mock(CompanionEffectiveModelService.class);
    private final CompanionPrivateModelService privateModels = mock(CompanionPrivateModelService.class);
    private final CompanionProfileServiceImpl service = new CompanionProfileServiceImpl(
            agentDao, agentService, templateService, snapshotService, modelConfigService, timbreService,
            voiceCloneService, subscriptionService, sysUserDao, profileModelDao, effectiveModels, privateModels);

    @Test
    void privateModelCannotBeSavedAndExistingBindingsRemainForMigration() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelSaveDTO binding = new CompanionProfileModelSaveDTO();
        binding.setModelType("LLM"); binding.setSource("private"); binding.setResourceId("private-1");
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("个人模型已停用，请选择系统模型", error.getMsg());
        verify(profileModelDao, never()).deleteByAgentId("agent-id");
        verify(profileModelDao, never()).insert(any(CompanionProfileModelEntity.class));
        verify(agentService, never()).updateById(any());
        verify(privateModels, never()).requireOwned(any(), any());
    }

    @Test
    void defaultSelectionsMaterializeUniqueEnabledDefaultsForAllRuntimeFields() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        for (String type : List.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory")) {
            ModelConfigEntity defaults = model(type.toLowerCase() + "-default", type, 1);
            defaults.setIsDefault(1);
            when(modelConfigService.getEnabledModelsByType(type)).thenReturn(List.of(defaults));
        }
        when(timbreService.hasTimbresForModel("tts-default")).thenReturn(false);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(
                binding("LLM", "default", null), binding("ASR", "default", null),
                binding("TTS", "default", null), binding("VAD", "default", null),
                binding("VLLM", "default", null), binding("Memory", "default", null)));
        dto.setTtsVoiceId("");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("llm-default", saved.getValue().getLlmModelId());
        assertEquals("asr-default", saved.getValue().getAsrModelId());
        assertEquals("tts-default", saved.getValue().getTtsModelId());
        assertEquals("vad-default", saved.getValue().getVadModelId());
        assertEquals("vllm-default", saved.getValue().getVllmModelId());
        assertEquals("memory-default", saved.getValue().getMemModelId());
        ArgumentCaptor<CompanionProfileModelEntity> inserted =
                ArgumentCaptor.forClass(CompanionProfileModelEntity.class);
        verify(profileModelDao, times(6)).insert(inserted.capture());
        assertTrue(inserted.getAllValues().stream().allMatch(binding ->
                "default".equals(binding.getSourceType()) && binding.getResourceId() == null));
    }

    @Test
    void rejectsDefaultSelectionWhenNoEnabledDefaultExists() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(modelConfigService.getEnabledModelsByType("ASR")).thenReturn(List.of());
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("ASR", "default", null)));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("默认模型不存在或不唯一", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(profileModelDao, never()).deleteByAgentId("agent-id");
    }

    @Test
    void rejectsDefaultSelectionWhenMultipleEnabledDefaultsExist() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        ModelConfigEntity first = model("vad-default-1", "VAD", 1);
        first.setIsDefault(1);
        ModelConfigEntity second = model("vad-default-2", "VAD", 1);
        second.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("VAD")).thenReturn(List.of(first, second));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("VAD", "default", null)));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("默认模型不存在或不唯一", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(profileModelDao, never()).deleteByAgentId("agent-id");
    }

    @Test
    void invalidReplacementDoesNotDeleteExistingPrivateBindings() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(modelConfigService.selectById("LLM_Disabled")).thenReturn(model("LLM_Disabled", "LLM", 0));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("LLM", "global", "LLM_Disabled")));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("全局模型类型或状态不匹配", error.getMsg());
        verify(profileModelDao, never()).deleteByAgentId("agent-id");
        verify(agentService, never()).updateById(any());
    }

    @Test
    void changingOneModelPreservesUnavailableLegacyModels() {
        AgentEntity existing = profile(7L);
        existing.setAsrModelId("ASR_Disabled");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(modelConfigService.selectById("llm-1")).thenReturn(null);
        when(modelConfigService.selectById("ASR_Disabled")).thenReturn(model("ASR_Disabled", "ASR", 0));
        when(modelConfigService.selectById("TTS_New")).thenReturn(model("TTS_New", "TTS", 1));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(
                binding("LLM", "global", "llm-1"),
                binding("ASR", "global", "ASR_Disabled"),
                binding("TTS", "global", "TTS_New")));
        dto.setTtsVoiceId("");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("llm-1", saved.getValue().getLlmModelId());
        assertEquals("ASR_Disabled", saved.getValue().getAsrModelId());
        assertEquals("TTS_New", saved.getValue().getTtsModelId());
    }

    @Test
    void changingOneModelPreservesUnavailableExplicitGlobalBinding() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity oldBinding = new CompanionProfileModelEntity();
        oldBinding.setModelType("ASR");
        oldBinding.setSourceType("global");
        oldBinding.setResourceId("ASR_Deleted");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(oldBinding));
        when(modelConfigService.selectById("ASR_Deleted")).thenReturn(null);
        when(modelConfigService.selectById("TTS_New")).thenReturn(model("TTS_New", "TTS", 1));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(
                binding("ASR", "global", "ASR_Deleted"),
                binding("TTS", "global", "TTS_New")));
        dto.setTtsVoiceId("");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("ASR_Deleted", saved.getValue().getAsrModelId());
        assertEquals("TTS_New", saved.getValue().getTtsModelId());
    }

    @Test
    void changingOneModelPreservesOverridesOnUnchangedExplicitBinding() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity llmBinding = new CompanionProfileModelEntity();
        llmBinding.setModelType("LLM");
        llmBinding.setSourceType("global");
        llmBinding.setResourceId("llm-1");
        llmBinding.setOverrideJson(new cn.hutool.json.JSONObject(Map.of("model", "legacy-model")));
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(llmBinding));
        when(modelConfigService.selectById("TTS_New")).thenReturn(model("TTS_New", "TTS", 1));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(
                binding("LLM", "global", "llm-1"),
                binding("TTS", "global", "TTS_New")));
        dto.setTtsVoiceId("");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<CompanionProfileModelEntity> inserted =
                ArgumentCaptor.forClass(CompanionProfileModelEntity.class);
        verify(profileModelDao, times(2)).insert(inserted.capture());
        CompanionProfileModelEntity savedLlm = inserted.getAllValues().stream()
                .filter(item -> "LLM".equals(item.getModelType()))
                .findFirst().orElseThrow();
        assertEquals("legacy-model", savedLlm.getOverrideJson().getStr("model"));
    }

    @Test
    void changingExplicitBindingClearsPreviousOverridesWhenRequestOmitsThem() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity llmBinding = new CompanionProfileModelEntity();
        llmBinding.setModelType("LLM");
        llmBinding.setSourceType("global");
        llmBinding.setResourceId("llm-1");
        llmBinding.setOverrideJson(new cn.hutool.json.JSONObject(Map.of("model", "legacy-model")));
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(llmBinding));
        when(modelConfigService.selectById("llm-2")).thenReturn(model("llm-2", "LLM", 1));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("LLM", "global", "llm-2")));

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<CompanionProfileModelEntity> inserted =
                ArgumentCaptor.forClass(CompanionProfileModelEntity.class);
        verify(profileModelDao).insert(inserted.capture());
        assertNull(inserted.getValue().getOverrideJson());
    }

    @Test
    void explicitBindingPreventsDifferentLegacyResourceFromBeingTreatedAsUnchanged() {
        AgentEntity existing = profile(7L);
        existing.setAsrModelId("ASR_Deleted_Legacy");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("ASR");
        explicit.setSourceType("global");
        explicit.setResourceId("ASR_Current");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(modelConfigService.selectById("ASR_Deleted_Legacy")).thenReturn(null);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("ASR", "global", "ASR_Deleted_Legacy")));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("全局模型类型或状态不匹配", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void nullGlobalResourceIsNeverTreatedAsUnchanged() {
        AgentEntity existing = profile(7L);
        existing.setAsrModelId(null);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("ASR", "global", null)));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("全局模型类型或状态不匹配", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void modelOptionsDelegateToTheSharedEffectiveModelService() {
        when(agentService.selectById("agent-id")).thenReturn(profile(7L));
        CompanionModelOptionVO option = new CompanionModelOptionVO(
                "p1", "TTS", "个人语音", "private", "edge", true);
        option.setVendorName("微软");
        option.setProtocol("Edge TTS");
        option.setCredentialStatus("not_required");
        when(effectiveModels.options(7L)).thenReturn(List.of(option));

        List<CompanionModelOptionVO> result = service.modelOptions(7L, "agent-id");

        assertEquals(List.of(option), result);
        assertEquals("微软", result.getFirst().getVendorName());
        assertEquals("not_required", result.getFirst().getCredentialStatus());
        verify(effectiveModels).options(7L);
    }

    @Test
    void ownerCanUpdateCompanionFieldsWithoutChangingProtectedFields() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(timbreService.selectById("voice-2")).thenReturn(timbre("voice-2", "tts-1", "普通话"));
        when(timbreService.getDefaultLanguage("普通话")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setAgentName("Mochi");
        dto.setRelationMode("lover");
        dto.setUserAddress("captain");
        dto.setPersonality("warm");
        dto.setSystemPrompt("new prompt");
        dto.setTtsVoiceId("voice-2");
        dto.setCompanionCueConfig("{\"idle\":true}");
        dto.setScreenExpressionEnabled(0);
        dto.setCameraPreferenceEnabled(0);

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        AgentEntity updated = saved.getValue();
        assertNotSame(existing, updated);
        assertEquals("Mochi", updated.getAgentName());
        assertEquals("lover", updated.getRelationMode());
        assertEquals("voice-2", updated.getTtsVoiceId());
        assertEquals(7L, updated.getUserId());
        assertEquals("llm-1", updated.getLlmModelId());
        assertEquals("template-1", updated.getCompanionTemplateId());
        InOrder order = inOrder(snapshotService, agentService);
        order.verify(snapshotService).createSnapshot("agent-id", "current");
        order.verify(agentService).updateById(updated);
        order.verify(snapshotService).createSnapshot("agent-id", "companion-update");
    }

    @Test
    void rejectsUnknownSubmittedTtsVoice() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("missing-voice");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 音色不存在", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void rejectsWhitespaceSubmittedTtsVoiceInsteadOfTreatingItAsClear() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("  ");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 音色不存在", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void rejectsRegularTtsVoiceFromDifferentModel() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(timbreService.selectById("voice-2")).thenReturn(timbre("voice-2", "tts-2", "普通话、粤语"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-2");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(profileModelDao).selectByAgentIdForUpdate("agent-id");
    }

    @Test
    void rejectsRegularTtsVoiceWithNullModelWhenRuntimeTtsModelIsNull() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId(null);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-explicit");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(timbreService.selectById("voice-null-model"))
                .thenReturn(timbre("voice-null-model", null, "普通话"));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-null-model");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void savesRegularTtsVoiceAndItsFirstConfiguredLanguage() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(timbreService.selectById("voice-2")).thenReturn(timbre("voice-2", "tts-1", "，， ; 普通话；粤语"));
        when(timbreService.getDefaultLanguage("，， ; 普通话；粤语")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-2");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("voice-2", saved.getValue().getTtsVoiceId());
        assertEquals("普通话", saved.getValue().getTtsLanguage());
        verify(timbreService, never()).getDefaultLanguageById("voice-2");
    }

    @Test
    void submittedTtsVoiceIsValidatedAgainstModelSelectedInSameUpdate() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(modelConfigService.selectById("tts-2")).thenReturn(model("tts-2", "TTS", 1));
        when(timbreService.selectById("voice-2")).thenReturn(timbre("voice-2", "tts-1", "普通话"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "global", "tts-2")));
        dto.setTtsVoiceId("voice-2");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(profileModelDao, never()).deleteByAgentId("agent-id");
    }

    @Test
    void submittedTtsVoiceIsValidatedAgainstMaterializedDefaultModel() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        ModelConfigEntity defaultTts = model("tts-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.selectById("voice-default"))
                .thenReturn(timbre("voice-default", "tts-default", "普通话"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "default", null)));
        dto.setTtsVoiceId("voice-default");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("tts-default", saved.getValue().getTtsModelId());
        assertEquals("voice-default", saved.getValue().getTtsVoiceId());
        verify(modelConfigService).getEnabledModelsByType("TTS");
    }

    @Test
    void submittedVoiceRematerializesExplicitDefaultTtsBeforeValidation() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-old-default");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("default");
        explicit.setResourceId(null);
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        ModelConfigEntity defaultTts = model("tts-new-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.selectById("voice-new-default"))
                .thenReturn(timbre("voice-new-default", "tts-new-default", "普通话"));
        when(timbreService.getDefaultLanguage("普通话")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-new-default");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("tts-new-default", saved.getValue().getTtsModelId());
        assertEquals("voice-new-default", saved.getValue().getTtsVoiceId());
    }

    @Test
    void submittedVoiceMaterializesSynthesizedDefaultTtsBeforeValidation() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId(null);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of());
        ModelConfigEntity defaultTts = model("tts-new-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.selectById("voice-new-default"))
                .thenReturn(timbre("voice-new-default", "tts-new-default", "普通话"));
        when(timbreService.getDefaultLanguage("普通话")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-new-default");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("tts-new-default", saved.getValue().getTtsModelId());
        assertEquals("voice-new-default", saved.getValue().getTtsVoiceId());
    }

    @Test
    void submittedVoiceDoesNotRematerializeGlobalTts() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-global");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-global");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(timbreService.selectById("voice-global"))
                .thenReturn(timbre("voice-global", "tts-global", "普通话"));
        when(timbreService.getDefaultLanguage("普通话")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-global");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("tts-global", saved.getValue().getTtsModelId());
        verify(modelConfigService, never()).getEnabledModelsByType("TTS");
    }

    @Test
    void submittedVoiceRejectsMissingCurrentDefaultTts() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-old-default");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("default");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of());
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-new-default");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("默认模型不存在或不唯一", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(timbreService, never()).selectById(any());
    }

    @Test
    void submittedVoiceRejectsAmbiguousCurrentDefaultTts() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-old-default");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("default");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        ModelConfigEntity first = model("tts-default-1", "TTS", 1);
        first.setIsDefault(1);
        ModelConfigEntity second = model("tts-default-2", "TTS", 1);
        second.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(first, second));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-new-default");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("默认模型不存在或不唯一", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(timbreService, never()).selectById(any());
    }

    @Test
    void ordinaryUpdateWithoutVoiceDoesNotReadTtsBindingOrDefault() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setAgentName("Renamed");

        service.update(7L, "agent-id", dto);

        verify(profileModelDao, never()).selectByAgentIdForUpdate(any());
        verify(modelConfigService, never()).getEnabledModelsByType("TTS");
        verify(timbreService, never()).selectById(any());
        verify(timbreService, never()).hasTimbresForModel(any());
        verify(voiceCloneService, never()).selectById(any());
    }

    @Test
    void blankVoiceIsRejectedWhenMaterializedDefaultTtsHasRegularVoices() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        ModelConfigEntity defaultTts = model("tts-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.hasTimbresForModel("tts-default")).thenReturn(true);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "default", null)));
        dto.setTtsVoiceId("");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("请选择声音", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(timbreService).hasTimbresForModel("tts-default");
    }

    @Test
    void submittedTtsVoiceUsesPersistedRuntimeModelWhenExplicitBindingDisagrees() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-legacy");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-explicit");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(timbreService.selectById("voice-explicit"))
                .thenReturn(timbre("voice-explicit", "tts-explicit", "普通话"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-explicit");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(profileModelDao).selectByAgentIdForUpdate("agent-id");
    }

    @Test
    void submittedTtsVoiceMatchingPersistedRuntimeModelIgnoresDisagreeingExplicitBinding() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-runtime");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-explicit");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(timbreService.selectById("voice-runtime"))
                .thenReturn(timbre("voice-runtime", "tts-runtime", "普通话"));
        when(timbreService.getDefaultLanguage("普通话")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("voice-runtime");

        service.update(7L, "agent-id", dto);

        verify(agentService).updateById(any(AgentEntity.class));
        verify(profileModelDao).selectByAgentIdForUpdate("agent-id");
    }

    @Test
    void omittedTtsVoicePreservesLegacyValueAndLanguageWithoutRevalidation() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId(null);
        existing.setTtsVoiceId("legacy-missing-voice");
        existing.setTtsLanguage("legacy-language");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);

        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setAgentName("Renamed");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("legacy-missing-voice", saved.getValue().getTtsVoiceId());
        assertEquals("legacy-language", saved.getValue().getTtsLanguage());
        verify(timbreService, never()).selectById(any());
        verify(timbreService, never()).getDefaultLanguageById(any());
        verify(timbreService, never()).hasTimbresForModel(any());
        verify(voiceCloneService, never()).selectById(any());
        verify(modelConfigService, never()).getEnabledModelsByType("TTS");
    }

    @Test
    void explicitBlankTtsVoiceIsRejectedWhenRuntimeModelHasRegularTimbres() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(timbreService.hasTimbresForModel("tts-1")).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("请选择声音", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(timbreService).hasTimbresForModel("tts-1");
    }

    @Test
    void explicitBlankTtsVoiceIsAllowedWhenRuntimeModelHasNoRegularTimbres() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-provider-default");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(timbreService.hasTimbresForModel("tts-provider-default")).thenReturn(false);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("", saved.getValue().getTtsVoiceId());
        verify(timbreService).hasTimbresForModel("tts-provider-default");
    }

    @Test
    void changingGlobalTtsModelWithoutSubmittingVoiceRejectsRetainedMismatchedVoice() {
        AgentEntity existing = profile(7L);
        existing.setTtsLanguage("legacy-language");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(modelConfigService.selectById("tts-2")).thenReturn(model("tts-2", "TTS", 1));
        when(timbreService.selectById("voice-1")).thenReturn(timbre("voice-1", "tts-1", "普通话"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "global", "tts-2")));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(profileModelDao, never()).deleteByAgentId("agent-id");
    }

    @Test
    void changingGlobalTtsModelWithoutSubmittingVoiceAcceptsMatchingVoiceWithoutRewritingLanguage() {
        AgentEntity existing = profile(7L);
        existing.setTtsLanguage("legacy-language");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(modelConfigService.selectById("tts-2")).thenReturn(model("tts-2", "TTS", 1));
        when(timbreService.selectById("voice-1")).thenReturn(timbre("voice-1", "tts-2", "新语言"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "global", "tts-2")));

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("legacy-language", saved.getValue().getTtsLanguage());
        verify(timbreService).selectById("voice-1");
        verify(timbreService, never()).getDefaultLanguageById("voice-1");
    }

    @Test
    void changingGlobalTtsModelToDefaultWithoutSubmittingVoiceRejectsRetainedVoice() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        ModelConfigEntity defaultTts = model("tts-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.selectById("voice-1")).thenReturn(timbre("voice-1", "tts-1", "普通话"));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "default", null)));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void changingExplicitGlobalTtsToDefaultWithSameRuntimeRejectsOmittedEmptyVoice() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-default");
        existing.setTtsVoiceId("");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-default");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        ModelConfigEntity defaultTts = model("tts-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.hasTimbresForModel("tts-default")).thenReturn(true);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "default", null)));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("请选择声音", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(timbreService).hasTimbresForModel("tts-default");
    }

    @Test
    void unchangedDefaultTtsIsNotRevalidatedWhenAnotherBindingChanges() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-default");
        existing.setTtsVoiceId("");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicitTts = new CompanionProfileModelEntity();
        explicitTts.setModelType("TTS");
        explicitTts.setSourceType("default");
        explicitTts.setResourceId(null);
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicitTts));
        ModelConfigEntity defaultTts = model("tts-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(modelConfigService.selectById("llm-2")).thenReturn(model("llm-2", "LLM", 1));
        when(timbreService.hasTimbresForModel("tts-default")).thenReturn(true);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(
                binding("TTS", "default", null),
                binding("LLM", "global", "llm-2")));

        service.update(7L, "agent-id", dto);

        verify(agentService).updateById(any(AgentEntity.class));
        verify(timbreService, never()).hasTimbresForModel(any());
        verify(timbreService, never()).selectById(any());
        verify(voiceCloneService, never()).selectById(any());
    }

    @Test
    void changingSynthesizedGlobalTtsToDefaultWithSameRuntimeRejectsOmittedEmptyVoice() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId("tts-default");
        existing.setTtsVoiceId("");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of());
        ModelConfigEntity defaultTts = model("tts-default", "TTS", 1);
        defaultTts.setIsDefault(1);
        when(modelConfigService.getEnabledModelsByType("TTS")).thenReturn(List.of(defaultTts));
        when(timbreService.hasTimbresForModel("tts-default")).thenReturn(true);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "default", null)));

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("请选择声音", error.getMsg());
        verify(agentService, never()).updateById(any());
        verify(timbreService).hasTimbresForModel("tts-default");
    }

    @Test
    void explicitBlankTtsVoiceClearsOnlyVoiceWithoutLookup() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId(null);
        existing.setTtsLanguage("普通话");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-explicit");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("", saved.getValue().getTtsVoiceId());
        assertEquals("普通话", saved.getValue().getTtsLanguage());
        verify(timbreService, never()).selectById(any());
        verify(timbreService, never()).getDefaultLanguageById(any());
        verify(timbreService, never()).hasTimbresForModel(any());
        verify(voiceCloneService, never()).selectById(any());
        verify(modelConfigService, never()).getEnabledModelsByType("TTS");
    }

    @Test
    void rejectsCloneTtsVoiceOwnedByAnotherUser() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(voiceCloneService.selectById("clone-voice"))
                .thenReturn(clone("clone-voice", 8L, "tts-1", 2, "普通话"));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("clone-voice");

        assertCode(ErrorCode.VOICE_RESOURCE_NO_PERMISSION, () -> service.update(7L, "agent-id", dto));

        verify(agentService, never()).updateById(any());
    }

    @Test
    void rejectsCloneTtsVoiceFromDifferentModel() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(voiceCloneService.selectById("clone-voice"))
                .thenReturn(clone("clone-voice", 7L, "tts-2", 2, "普通话"));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("clone-voice");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void rejectsCloneTtsVoiceWithNullModelWhenRuntimeTtsModelIsNull() {
        AgentEntity existing = profile(7L);
        existing.setTtsModelId(null);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        CompanionProfileModelEntity explicit = new CompanionProfileModelEntity();
        explicit.setModelType("TTS");
        explicit.setSourceType("global");
        explicit.setResourceId("tts-explicit");
        when(profileModelDao.selectByAgentIdForUpdate("agent-id")).thenReturn(List.of(explicit));
        when(voiceCloneService.selectById("clone-null-model"))
                .thenReturn(clone("clone-null-model", 7L, null, 2, "普通话"));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("clone-null-model");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void rejectsCloneTtsVoiceThatHasNotFinishedTraining() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(voiceCloneService.selectById("clone-voice"))
                .thenReturn(clone("clone-voice", 7L, "tts-1", 1, "普通话"));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("clone-voice");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 克隆音色尚未训练完成", error.getMsg());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void savesOwnedTrainedCloneTtsVoiceAndItsFirstConfiguredLanguage() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(voiceCloneService.selectById("clone-voice"))
                .thenReturn(clone("clone-voice", 7L, "tts-1", 2, "、, English，中文"));
        when(timbreService.getDefaultLanguage("、, English，中文")).thenReturn("English");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("clone-voice");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("clone-voice", saved.getValue().getTtsVoiceId());
        assertEquals("English", saved.getValue().getTtsLanguage());
        verify(timbreService, never()).getDefaultLanguageById("clone-voice");
    }

    @Test
    void cloneTtsVoiceUsesModelSelectedInSameUpdate() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(modelConfigService.selectById("tts-2")).thenReturn(model("tts-2", "TTS", 1));
        when(voiceCloneService.selectById("clone-voice"))
                .thenReturn(clone("clone-voice", 7L, "tts-2", 2, "普通话"));
        when(timbreService.getDefaultLanguage("普通话")).thenReturn("普通话");
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenReturn(1);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("TTS", "global", "tts-2")));
        dto.setTtsVoiceId("clone-voice");

        service.update(7L, "agent-id", dto);

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        assertEquals("tts-2", saved.getValue().getTtsModelId());
        assertEquals("clone-voice", saved.getValue().getTtsVoiceId());
    }

    @Test
    void regularTtsVoiceValidationWinsWhenRegularAndCloneIdsCollide() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(timbreService.selectById("shared-voice"))
                .thenReturn(timbre("shared-voice", "tts-2", "普通话"));
        when(voiceCloneService.selectById("shared-voice"))
                .thenReturn(clone("shared-voice", 7L, "tts-1", 2, "English"));
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setTtsVoiceId("shared-voice");

        RenException error = assertThrows(RenException.class, () -> service.update(7L, "agent-id", dto));

        assertEquals("ttsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
        verify(voiceCloneService, never()).selectById("shared-voice");
    }

    @Test
    void failedUpdateDoesNotMutateLoadedEntity() {
        AgentEntity existing = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(false);
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setAgentName("changed");

        assertCode(ErrorCode.UPDATE_DATA_FAILED, () -> service.update(7L, "agent-id", dto));

        assertEquals("Original", existing.getAgentName());
    }

    @Test
    void foreignUserCannotReadOrUpdateProfile() {
        when(agentService.selectById("agent-id")).thenReturn(profile(8L));
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(8L));

        assertCode(ErrorCode.NO_PERMISSION, () -> service.get(7L, "agent-id"));
        assertCode(ErrorCode.NO_PERMISSION,
                () -> service.update(7L, "agent-id", new CompanionProfileSaveDTO()));
        verify(snapshotService, never()).createSnapshot(any(), any());
        verify(agentService, never()).updateById(any());
    }

    @Test
    void ordinaryAgentIsNotExposedThroughAnyProfileOperation() {
        AgentEntity ordinaryAgent = profile(7L);
        ordinaryAgent.setCompanionEnabled(0);
        when(agentService.selectById("agent-id")).thenReturn(ordinaryAgent);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(ordinaryAgent);

        assertCode(ErrorCode.AGENT_NOT_FOUND, () -> service.get(7L, "agent-id"));
        assertCode(ErrorCode.AGENT_NOT_FOUND,
                () -> service.update(7L, "agent-id", new CompanionProfileSaveDTO()));
        assertCode(ErrorCode.AGENT_NOT_FOUND, () -> service.restorePrompt(7L, "agent-id"));
        assertCode(ErrorCode.AGENT_NOT_FOUND, () -> service.delete(7L, "agent-id"));

        verify(snapshotService, never()).createSnapshot(any(), any());
        verify(agentService, never()).updateById(any());
        verify(agentService, never()).deleteAgent(any());
        verify(agentDao, never()).getDeviceCountByAgentId(any());
    }

    @Test
    void profileWithoutDisplayResourcesStillReturnsSafely() {
        AgentEntity profile = profile(7L);
        profile.setLlmModelId(null);
        profile.setTtsModelId(null);
        profile.setTtsVoiceId(null);
        when(agentService.selectById("agent-id")).thenReturn(profile);
        when(modelConfigService.getModelNamesByIds(Set.of())).thenReturn(Map.of());
        when(timbreService.getTimbreNamesByIds(Set.of())).thenReturn(Map.of());

        CompanionProfileVO result = service.get(7L, "agent-id");

        assertEquals("agent-id", result.getId());
        assertEquals(null, result.getLlmModelName());
        assertEquals(null, result.getTtsVoiceName());
    }

    @Test
    void profileReadSynthesizesEditableBindingsFromLegacyAgentModels() {
        AgentEntity profile = profile(7L);
        profile.setAsrModelId("asr-1");
        profile.setVadModelId("vad-1");
        profile.setVllmModelId("vllm-1");
        profile.setMemModelId("memory-1");
        when(agentService.selectById("agent-id")).thenReturn(profile);
        when(modelConfigService.getModelNamesByIds(Set.of("llm-1", "tts-1"))).thenReturn(Map.of());
        when(timbreService.getTimbreNamesByIds(Set.of("voice-1"))).thenReturn(Map.of());
        when(profileModelDao.selectByAgentId("agent-id")).thenReturn(List.of());
        when(modelConfigService.selectById("llm-1")).thenReturn(model("llm-1", "LLM", 1));
        when(modelConfigService.selectById("asr-1")).thenReturn(model("asr-1", "ASR", 1));
        when(modelConfigService.selectById("tts-1")).thenReturn(model("tts-1", "TTS", 1));
        when(modelConfigService.selectById("vad-1")).thenReturn(model("vad-1", "VAD", 1));
        when(modelConfigService.selectById("vllm-1")).thenReturn(model("vllm-1", "VLLM", 1));
        when(modelConfigService.selectById("memory-1")).thenReturn(model("memory-1", "Memory", 1));

        CompanionProfileVO result = service.get(7L, "agent-id");

        assertEquals("voice-1", result.getTtsVoiceId());
        assertEquals(List.of("LLM:global:llm-1", "ASR:global:asr-1", "TTS:global:tts-1",
                "VAD:global:vad-1", "VLLM:global:vllm-1", "Memory:global:memory-1"),
                result.getModels().stream()
                        .map(item -> item.getModelType() + ":" + item.getSource() + ":" + item.getResourceId())
                        .toList());
    }

    @Test
    void profileReadSynthesizesDefaultsForMissingLegacyAgentModels() {
        AgentEntity profile = profile(7L);
        profile.setAsrModelId(" ");
        profile.setVadModelId(null);
        profile.setVllmModelId(null);
        profile.setMemModelId(null);
        when(agentService.selectById("agent-id")).thenReturn(profile);
        when(modelConfigService.getModelNamesByIds(Set.of("llm-1", "tts-1"))).thenReturn(Map.of());
        when(timbreService.getTimbreNamesByIds(Set.of("voice-1"))).thenReturn(Map.of());
        when(profileModelDao.selectByAgentId("agent-id")).thenReturn(List.of());
        when(modelConfigService.selectById("llm-1")).thenReturn(model("llm-1", "LLM", 1));
        when(modelConfigService.selectById("tts-1")).thenReturn(model("tts-1", "TTS", 1));

        CompanionProfileVO result = service.get(7L, "agent-id");

        assertEquals(List.of("LLM:global", "ASR:default", "TTS:global", "VAD:default",
                "VLLM:default", "Memory:default"), result.getModels().stream()
                .map(item -> item.getModelType() + ":" + item.getSource()).toList());
        assertEquals(null, result.getModels().get(1).getResourceId());
    }

    @Test
    void profileReadMarksLegacyPrivateBindingUnavailable() {
        AgentEntity profile = profile(7L);
        when(agentService.selectById("agent-id")).thenReturn(profile);
        when(modelConfigService.getModelNamesByIds(Set.of("llm-1", "tts-1"))).thenReturn(Map.of());
        when(timbreService.getTimbreNamesByIds(Set.of("voice-1"))).thenReturn(Map.of());
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM");
        binding.setSourceType("private");
        binding.setResourceId("private-1");
        when(profileModelDao.selectByAgentId("agent-id")).thenReturn(List.of(binding));

        CompanionProfileVO result = service.get(7L, "agent-id");

        CompanionProfileModelVO model = result.getModels().getFirst();
        assertEquals("旧个人模型", model.getName());
        assertEquals(false, model.isEnabled());
        assertEquals("旧个人模型已停用，请重新选择", model.getUnavailableReason());
        verify(privateModels, never()).requireOwned(any(), any());
    }

    @Test
    void profileReadMarksDisabledGlobalBindingUnavailable() {
        AgentEntity profile = profile(7L);
        when(agentService.selectById("agent-id")).thenReturn(profile);
        when(modelConfigService.getModelNamesByIds(Set.of("llm-1", "tts-1"))).thenReturn(Map.of());
        when(timbreService.getTimbreNamesByIds(Set.of("voice-1"))).thenReturn(Map.of());
        CompanionProfileModelEntity binding = new CompanionProfileModelEntity();
        binding.setModelType("LLM");
        binding.setSourceType("global");
        binding.setResourceId("LLM_Disabled");
        ModelConfigEntity disabled = model("LLM_Disabled", "LLM", 0);
        disabled.setModelName("已停用模型");
        when(profileModelDao.selectByAgentId("agent-id")).thenReturn(List.of(binding));
        when(modelConfigService.selectById("LLM_Disabled")).thenReturn(disabled);

        CompanionProfileModelVO result = service.get(7L, "agent-id").getModels().getFirst();

        assertEquals("已停用模型", result.getName());
        assertEquals(false, result.isEnabled());
        assertEquals("模型已停用，请重新选择", result.getUnavailableReason());
    }

    @Test
    void migrationStatusIsSerializedAsTopLevelModelFields() throws Exception {
        CompanionProfileModelVO model = new CompanionProfileModelVO();
        model.setModelType("LLM");
        model.setSource("private");
        model.setResourceId("private-1");
        model.setName("旧个人模型");
        model.setEnabled(false);
        model.setUnavailableReason("旧个人模型已停用，请重新选择");
        CompanionProfileVO profile = new CompanionProfileVO();
        profile.setModels(List.of(model));
        var effective = new xiaozhi.modules.companion.model.vo.CompanionEffectiveModelVO();
        effective.setModelType("LLM");
        effective.setResourceId("private-1");
        effective.setName("旧个人模型");
        effective.setSource("private");
        effective.setEnabled(false);
        effective.setUnavailableReason("旧个人模型已停用，请重新选择");
        effective.setOverrides(Map.of());
        profile.setEffectiveModels(List.of(effective));

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(profile));

        assertEquals(false, json.at("/models/0/enabled").asBoolean());
        assertEquals("旧个人模型", json.at("/models/0/name").asText());
        assertEquals("旧个人模型已停用，请重新选择", json.at("/models/0/unavailableReason").asText());
        assertTrue(json.at("/models/0/overrides/enabled").isMissingNode());
        assertEquals("旧个人模型", json.at("/effectiveModels/0/name").asText());
        assertEquals(false, json.at("/effectiveModels/0/enabled").asBoolean());
        assertEquals("旧个人模型已停用，请重新选择",
                json.at("/effectiveModels/0/unavailableReason").asText());
    }

    @Test
    void failedSecondBindingInsertRollsBackAgentAndOldBinding() {
        AgentEntity existing = profile(7L);
        existing.setLlmModelId("LLM_Old");
        existing.setTtsModelId("TTS_Old");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(modelConfigService.selectById("LLM_New")).thenReturn(model("LLM_New", "LLM", 1));
        when(modelConfigService.selectById("TTS_New")).thenReturn(model("TTS_New", "TTS", 1));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:companion_binding_tx_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tx_agent (id VARCHAR(32) PRIMARY KEY, llm_model_id VARCHAR(32), tts_model_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE tx_binding (agent_id VARCHAR(32), model_type VARCHAR(16), source_type VARCHAR(16), resource_id VARCHAR(32))");
        jdbc.update("INSERT INTO tx_agent(id, llm_model_id, tts_model_id) VALUES ('agent-id', 'LLM_Old', 'TTS_Old')");
        jdbc.update("INSERT INTO tx_binding(agent_id, model_type, source_type, resource_id) VALUES ('agent-id', 'LLM', 'private', 'private-old')");
        when(agentService.updateById(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity agent = invocation.getArgument(0);
            return jdbc.update("UPDATE tx_agent SET llm_model_id=?, tts_model_id=? WHERE id=?",
                    agent.getLlmModelId(), agent.getTtsModelId(), agent.getId()) == 1;
        });
        when(profileModelDao.deleteByAgentId("agent-id")).thenAnswer(invocation ->
                jdbc.update("DELETE FROM tx_binding WHERE agent_id='agent-id'"));
        AtomicInteger inserts = new AtomicInteger();
        when(profileModelDao.insert(any(CompanionProfileModelEntity.class))).thenAnswer(invocation -> {
            CompanionProfileModelEntity binding = invocation.getArgument(0);
            if (inserts.incrementAndGet() == 2) return 0;
            return jdbc.update("INSERT INTO tx_binding(agent_id, model_type, source_type, resource_id) VALUES (?, ?, ?, ?)",
                    binding.getAgentId(), binding.getModelType(), binding.getSourceType(), binding.getResourceId());
        });
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        CompanionProfileService transactionalService = (CompanionProfileService) proxyFactory.getProxy();
        CompanionProfileSaveDTO dto = new CompanionProfileSaveDTO();
        dto.setModels(List.of(binding("LLM", "global", "LLM_New"), binding("TTS", "global", "TTS_New")));
        dto.setTtsVoiceId("");

        RenException error = assertThrows(RenException.class,
                () -> transactionalService.update(7L, "agent-id", dto));

        assertEquals("保存角色模型失败", error.getMsg());
        assertEquals("LLM_Old", jdbc.queryForObject(
                "SELECT llm_model_id FROM tx_agent WHERE id='agent-id'", String.class));
        assertEquals("TTS_Old", jdbc.queryForObject(
                "SELECT tts_model_id FROM tx_agent WHERE id='agent-id'", String.class));
        assertEquals(2, inserts.get());
        assertEquals(List.of("private:private-old"), jdbc.query(
                "SELECT source_type, resource_id FROM tx_binding WHERE agent_id='agent-id'",
                (rs, rowNum) -> rs.getString(1) + ":" + rs.getString(2)));
    }

    @Test
    void createFromTemplateCopiesRunnableConfigurationAndCreatesInitialSnapshot() {
        AgentTemplateEntity template = template();
        when(templateService.getById("template-1")).thenReturn(template);
        when(agentService.insert(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            entity.setId("new-agent");
            return true;
        });

        String created = service.createFromTemplate(7L, "template-1", "Nova");

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).insert(saved.capture());
        AgentEntity entity = saved.getValue();
        assertEquals(7L, entity.getUserId());
        assertEquals("Nova", entity.getAgentName());
        assertEquals("template-1", entity.getCompanionTemplateId());
        assertEquals("{\"laugh\":\"laugh.wav\"}", entity.getCompanionCueConfig());
        assertEquals(1, entity.getCompanionEnabled());
        assertEquals("friend", entity.getRelationMode());
        assertEquals(1, entity.getScreenExpressionEnabled());
        assertEquals(1, entity.getCameraPreferenceEnabled());
        assertEquals("llm-1", entity.getLlmModelId());
        assertEquals("voice-1", entity.getTtsVoiceId());
        assertEquals("prompt from template", entity.getSystemPrompt());
        assertEquals("memory from template", entity.getSummaryMemory());
        assertEquals("zh", entity.getLanguage());
        assertEquals("new-agent", created);
        InOrder boundary = inOrder(sysUserDao, agentDao, subscriptionService, agentService, snapshotService);
        boundary.verify(sysUserDao).selectByIdForUpdate(7L);
        boundary.verify(agentDao).selectCount(any());
        boundary.verify(subscriptionService).requireProfileSlot(7L, 0);
        boundary.verify(agentService).insert(any(AgentEntity.class));
        boundary.verify(snapshotService).createSnapshot("new-agent", "initial");
    }

    @Test
    void deviceBindingLocksUserBeforeRecheckingAndCreatingDefaultProfile() {
        when(agentDao.selectList(anyAgentWrapper())).thenReturn(List.of());
        when(templateService.getById("template-xiaozhi")).thenReturn(template());
        when(agentService.insert(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            entity.setId("profile-xiaozhi");
            return true;
        });

        String profileId = service.resolveForDeviceBinding(7L, null, "template-xiaozhi", "小智");

        assertEquals("profile-xiaozhi", profileId);
        InOrder order = inOrder(sysUserDao, agentDao, agentService);
        order.verify(sysUserDao).selectByIdForUpdate(7L);
        order.verify(agentDao).selectList(anyAgentWrapper());
        order.verify(agentDao).selectCount(anyAgentWrapper());
        order.verify(agentService).insert(any(AgentEntity.class));
    }

    @Test
    void deviceBindingReusesProfileFoundAfterUserLock() {
        AgentEntity existing = profile(7L);
        existing.setId("existing-profile");
        when(agentDao.selectList(anyAgentWrapper())).thenReturn(List.of(existing));

        String profileId = service.resolveForDeviceBinding(7L, null, "template-xiaozhi", "小智");

        assertEquals("existing-profile", profileId);
        verify(sysUserDao).selectByIdForUpdate(7L);
        verify(agentService, never()).insert(any());
        verify(subscriptionService, never()).requireProfileSlot(any(), anyLong());
    }

    @Test
    void restorePromptSnapshotsAndOnlyRestoresTemplatePrompt() {
        AgentEntity existing = profile(7L);
        existing.setAgentName("Custom name");
        existing.setRelationMode("lover");
        existing.setSystemPrompt("custom prompt");
        existing.setTtsVoiceId("custom-voice");
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);
        when(templateService.getById("template-1")).thenReturn(template());
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);

        service.restorePrompt(7L, "agent-id");

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).updateById(saved.capture());
        AgentEntity restored = saved.getValue();
        assertEquals("prompt from template", restored.getSystemPrompt());
        assertEquals("Custom name", restored.getAgentName());
        assertEquals("lover", restored.getRelationMode());
        assertEquals("custom-voice", restored.getTtsVoiceId());
        assertEquals("llm-1", restored.getLlmModelId());
        InOrder order = inOrder(agentDao, snapshotService, agentService);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(snapshotService).createSnapshot("agent-id", "current");
        order.verify(agentService).updateById(restored);
        order.verify(snapshotService).createSnapshot("agent-id", "companion-restore-prompt");
        verify(agentService, never()).selectById("agent-id");
    }

    @Test
    void restorePromptFailsWhenTemplateReferenceIsMissing() {
        AgentEntity existing = profile(7L);
        existing.setCompanionTemplateId(null);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(existing);

        assertCode(ErrorCode.AGENT_TEMPLATE_NOT_FOUND, () -> service.restorePrompt(7L, "agent-id"));
        verify(snapshotService, never()).createSnapshot(any(), any());
    }

    @Test
    void referencedProfileCannotBeDeleted() {
        AgentEntity locked = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(locked);
        when(agentDao.getDeviceCountByAgentId("agent-id")).thenReturn(1);

        assertCode(ErrorCode.DELETE_DATA_FAILED, () -> service.delete(7L, "agent-id"));

        InOrder order = inOrder(agentDao, agentService);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(agentDao).getDeviceCountByAgentId("agent-id");
        verify(agentService, never()).deleteAgent(any());
    }

    @Test
    void unreferencedProfileUsesExistingAgentDeletionFlow() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        when(agentDao.getDeviceCountByAgentId("agent-id")).thenReturn(0);

        service.delete(7L, "agent-id");

        verify(agentService).deleteAgent("agent-id");
    }

    @Test
    void failedUpdateRollsBackSnapshotAndAgentWritesUsingRealSpringTransaction() {
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(profile(7L));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:companion_tx_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tx_probe (kind VARCHAR(32) NOT NULL)");
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO tx_probe(kind) VALUES ('snapshot')");
            return null;
        }).when(snapshotService).createSnapshot("agent-id", "current");
        when(agentService.updateById(any(AgentEntity.class))).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tx_probe(kind) VALUES ('agent')");
            return false;
        });
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        CompanionProfileService transactionalService = (CompanionProfileService) proxyFactory.getProxy();

        assertCode(ErrorCode.UPDATE_DATA_FAILED,
                () -> transactionalService.update(7L, "agent-id", new CompanionProfileSaveDTO()));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class));
        InOrder order = inOrder(snapshotService, agentService);
        order.verify(snapshotService).createSnapshot("agent-id", "current");
        order.verify(agentService).updateById(any(AgentEntity.class));
        verify(snapshotService, never()).createSnapshot("agent-id", "companion-update");
    }

    @Test
    void updateLocksProfileBeforeSnapshotAndPersistence() {
        AgentEntity locked = profile(7L);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(locked);
        when(agentService.updateById(any(AgentEntity.class))).thenReturn(true);

        service.update(7L, "agent-id", new CompanionProfileSaveDTO());

        InOrder order = inOrder(agentDao, snapshotService, agentService);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(snapshotService).createSnapshot("agent-id", "current");
        order.verify(agentService).updateById(any(AgentEntity.class));
        order.verify(snapshotService).createSnapshot("agent-id", "companion-update");
        verify(agentService, never()).selectById("agent-id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listScopesQueryAndBatchLoadsDifferentDisplayIds() {
        AgentEntity first = profile(7L);
        AgentEntity second = profile(7L);
        second.setId("agent-2");
        second.setLlmModelId("llm-2");
        second.setTtsModelId("tts-2");
        second.setTtsVoiceId("voice-2");
        when(agentDao.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(modelConfigService.getModelNamesByIds(Set.of("llm-1", "tts-1", "llm-2", "tts-2")))
                .thenReturn(Map.of("llm-1", "GPT", "tts-1", "TTS", "llm-2", "Claude", "tts-2", "TTS 2"));
        when(timbreService.getTimbreNamesByIds(Set.of("voice-1", "voice-2")))
                .thenReturn(Map.of("voice-1", "Alice", "voice-2", "Bob"));

        List<CompanionProfileVO> result = service.list(7L);

        assertEquals(2, result.size());
        assertEquals("Original", result.get(0).getName());
        assertEquals("GPT", result.get(0).getLlmModelName());
        assertEquals("Alice", result.get(0).getTtsVoiceName());
        assertEquals("Claude", result.get(1).getLlmModelName());
        assertEquals("Bob", result.get(1).getTtsVoiceName());
        ArgumentCaptor<QueryWrapper<AgentEntity>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(agentDao).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getSqlSegment().contains("companion_enabled"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(1));
        verify(modelConfigService, times(1))
                .getModelNamesByIds(Set.of("llm-1", "tts-1", "llm-2", "tts-2"));
        verify(timbreService, times(1)).getTimbreNamesByIds(Set.of("voice-1", "voice-2"));
        verify(modelConfigService, never()).getModelNameById(any());
        verify(timbreService, never()).getTimbreNameById(any());
    }

    private AgentEntity profile(Long userId) {
        AgentEntity entity = new AgentEntity();
        entity.setId("agent-id");
        entity.setUserId(userId);
        entity.setAgentName("Original");
        entity.setLlmModelId("llm-1");
        entity.setTtsModelId("tts-1");
        entity.setTtsVoiceId("voice-1");
        entity.setCompanionEnabled(1);
        entity.setCompanionTemplateId("template-1");
        entity.setRelationMode("friend");
        return entity;
    }

    private CompanionProfileModelSaveDTO binding(String type, String source, String resourceId) {
        CompanionProfileModelSaveDTO binding = new CompanionProfileModelSaveDTO();
        binding.setModelType(type);
        binding.setSource(source);
        binding.setResourceId(resourceId);
        return binding;
    }

    private ModelConfigEntity model(String id, String type, int enabled) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setModelType(type);
        model.setIsEnabled(enabled);
        return model;
    }

    private TimbreEntity timbre(String id, String modelId, String languages) {
        TimbreEntity timbre = new TimbreEntity();
        timbre.setId(id);
        timbre.setTtsModelId(modelId);
        timbre.setLanguages(languages);
        return timbre;
    }

    private VoiceCloneEntity clone(String id, Long userId, String modelId, Integer trainStatus, String languages) {
        VoiceCloneEntity clone = new VoiceCloneEntity();
        clone.setId(id);
        clone.setUserId(userId);
        clone.setModelId(modelId);
        clone.setTrainStatus(trainStatus);
        clone.setLanguages(languages);
        return clone;
    }

    private Wrapper<AgentEntity> anyAgentWrapper() {
        return ArgumentMatchers.any();
    }

    private SysUserDao userDao() {
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        return dao;
    }

    private AgentTemplateEntity template() {
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-1");
        template.setAgentCode("template-code");
        template.setAsrModelId("asr-1");
        template.setVadModelId("vad-1");
        template.setLlmModelId("llm-1");
        template.setVllmModelId("vllm-1");
        template.setTtsModelId("tts-1");
        template.setTtsVoiceId("voice-1");
        template.setTtsLanguage("zh-CN");
        template.setMemModelId("memory-1");
        template.setIntentModelId("intent-1");
        template.setChatHistoryConf(2);
        template.setSystemPrompt("prompt from template");
        template.setCompanionCueConfig("{\"laugh\":\"laugh.wav\"}");
        template.setSummaryMemory("memory from template");
        template.setLangCode("zh-CN");
        template.setLanguage("zh");
        return template;
    }

    private void assertCode(int code, Executable executable) {
        try (MockedStatic<MessageUtils> messages = org.mockito.Mockito.mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(code)).thenReturn("error");
            RenException error = assertThrows(RenException.class, executable);
            assertEquals(code, error.getCode());
        }
    }
}
