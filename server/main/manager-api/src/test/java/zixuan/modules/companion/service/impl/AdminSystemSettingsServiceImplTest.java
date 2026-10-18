package zixuan.modules.companion.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.common.constant.Constant;
import zixuan.common.exception.RenException;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.companion.dto.AdminSystemSettingsSaveDTO;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.companion.vo.AdminSystemSettingsVO;
import zixuan.modules.config.service.ConfigService;
import zixuan.modules.model.dto.VoiceDTO;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.timbre.entity.TimbreEntity;
import zixuan.modules.timbre.service.TimbreService;

class AdminSystemSettingsServiceImplTest {
    private static final String PROACTIVE_PLANNER_PROMPT = "companion.proactive_planner_prompt";
    private final SysParamsService params = mock(SysParamsService.class);
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final AgentTemplateService templates = mock(AgentTemplateService.class);
    private final TimbreService timbres = mock(TimbreService.class);
    private final ConfigService config = mock(ConfigService.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final AdminSystemSettingsServiceImpl service = new AdminSystemSettingsServiceImpl(
            params, models, templates, timbres, config, audit);

    @Test
    void readsSafeSettingsAndUsesCurrentDefaultsForMissingListenValues() {
        when(params.getValue(Constant.SERVER_WEBSOCKET, false)).thenReturn("ws://pet.example/ws");
        when(params.getValue(Constant.SERVER_OTA, false)).thenReturn("https://pet.example/ota/");
        when(params.getValue(Constant.SERVER_IP, false)).thenReturn("0.0.0.0");
        when(params.getValue(Constant.SERVER_PORT, false)).thenReturn("8000");
        when(params.getValue(Constant.SERVER_OTA_IP, false)).thenReturn(null);
        when(params.getValue(Constant.SERVER_OTA_PORT, false)).thenReturn(null);
        when(templates.getDefaultTemplate()).thenReturn(template());
        when(models.getEnabledModelsByType("LLM")).thenReturn(List.of(model("llm-1", "LLM", "对话模型")));
        when(models.getEnabledModelsByType("VLLM")).thenReturn(List.of(model("vllm-1", "VLLM", "视觉模型")));
        when(models.getEnabledModelsByType("TTS")).thenReturn(List.of(model("tts-1", "TTS", "语音模型")));
        when(models.getEnabledModelsByType("ASR")).thenReturn(List.of(model("asr-1", "ASR", "识别模型")));
        when(models.getEnabledModelsByType("VAD")).thenReturn(List.of(model("vad-1", "VAD", "检测模型")));
        when(models.getEnabledModelsByType("Memory")).thenReturn(List.of(model("memory-1", "Memory", "记忆模型")));
        when(timbres.getVoiceNames("tts-1", null)).thenReturn(List.of(new VoiceDTO("voice-1", "温柔女声")));

        AdminSystemSettingsVO result = service.get();

        assertEquals("0.0.0.0", result.getOtaListenHost());
        assertEquals(8002, result.getOtaListenPort());
        assertEquals("llm-1", result.getDefaultLlmModelId());
        assertEquals(List.of("voice-1"), result.getVoices().stream().map(AdminSystemSettingsVO.Option::id).toList());
        assertEquals("unknown", result.getHealth().get("zixuan").status());
        assertEquals(List.of("llm-1"), result.getModelOptions().get("LLM").stream()
                .map(AdminSystemSettingsVO.Option::id).toList());
    }

    @Test
    void readsTheGlobalProactivePlannerPrompt() {
        when(params.getValue(PROACTIVE_PLANNER_PROMPT, false)).thenReturn("只在有自然切入点时主动陪伴");
        when(templates.getDefaultTemplate()).thenReturn(template());

        AdminSystemSettingsVO result = service.get();

        assertEquals("只在有自然切入点时主动陪伴", result.getProactivePlannerPrompt());
    }

    @Test
    void rejectsMalformedPublicWebsocketUrl() {
        AdminSystemSettingsSaveDTO request = validRequest();
        request.setPublicWebsocketUrl("https://pet.example/ws");

        RenException error = assertThrows(RenException.class, () -> service.save(7L, request));

        assertEquals("publicWebsocketUrl 格式不正确", error.getMsg());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsDisabledOrMismatchedModel() {
        AdminSystemSettingsSaveDTO request = validRequest();
        ModelConfigEntity wrong = model("llm-1", "TTS", "错误模型");
        when(models.selectById("llm-1")).thenReturn(wrong);

        RenException error = assertThrows(RenException.class, () -> service.save(7L, request));

        assertEquals("defaultLlmModelId 模型类型不匹配", error.getMsg());
    }

    @Test
    void rejectsVoiceOwnedByAnotherTtsModel() {
        stubValidResources();
        TimbreEntity voice = new TimbreEntity();
        voice.setId("voice-1");
        voice.setTtsModelId("tts-2");
        when(timbres.selectById("voice-1")).thenReturn(voice);

        RenException error = assertThrows(RenException.class, () -> service.save(7L, validRequest()));

        assertEquals("defaultTtsVoiceId 对应音色不属于所选 TTS 模型", error.getMsg());
    }

    @Test
    void savesAllValuesRefreshesCachesAndAuditsOnlySafeChanges() {
        stubCurrentSettings();
        stubValidResources();
        when(models.updateById(any(ModelConfigEntity.class))).thenReturn(true);
        when(timbres.getVoiceNames("tts-1", null)).thenReturn(List.of(new VoiceDTO("voice-1", "温柔女声")));

        AdminSystemSettingsVO saved = service.save(7L, validRequest());

        verify(params).upsertValueByCode(Constant.SERVER_WEBSOCKET, "wss://pet.example/ws", "string",
                "设备公开 WebSocket 地址");
        verify(params).upsertValueByCode(Constant.SERVER_OTA_PORT, "8002", "number", "OTA 服务监听端口");
        verify(templates).updateDefaultTemplateModelId("VLLM", "vllm-1");
        verify(templates).updateDefaultTemplateVoiceId("voice-1");
        verify(config).evictCache();
        verify(config).getConfig(false);
        ArgumentCaptor<Map<String, ?>> summary = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(7L), isNull(), eq("system-settings.update"), eq("system-settings"),
                isNull(), summary.capture());
        assertTrue(summary.getValue().containsKey("changedFields"));
        assertTrue(!summary.getValue().toString().contains("api_key"));
        assertTrue(saved.isRestartRequired());
        assertEquals(List.of("zixuan"), saved.getRestartServices());
    }

    @Test
    void savesTheGlobalProactivePlannerPromptWithoutPuttingItInAuditDetails() {
        stubCurrentSettings();
        stubValidResources();
        when(models.updateById(any(ModelConfigEntity.class))).thenReturn(true);
        AdminSystemSettingsSaveDTO request = validRequest();
        request.setProactivePlannerPrompt("只在用户真正安静时主动说一句");

        service.save(7L, request);

        verify(params).upsertValueByCode(PROACTIVE_PLANNER_PROMPT,
                "只在用户真正安静时主动说一句", "string", "主动陪伴全局规划提示词");
        ArgumentCaptor<Map<String, ?>> summary = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(7L), isNull(), eq("system-settings.update"), eq("system-settings"),
                isNull(), summary.capture());
        assertTrue(!summary.getValue().toString().contains("只在用户真正安静时主动说一句"));
    }

    @Test
    void cacheRefreshFailureIsReportedAndLeavesBothCachesEvicted() {
        stubCurrentSettings();
        stubValidResources();
        when(models.updateById(any(ModelConfigEntity.class))).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable")).when(config).getConfig(false);

        assertThrows(IllegalStateException.class, () -> service.save(7L, validRequest()));

        verify(params, times(2)).evictCache(any());
        verify(config, times(2)).evictCache();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    private void stubCurrentSettings() {
        when(params.getValue(Constant.SERVER_WEBSOCKET, false)).thenReturn("ws://old.example/ws");
        when(params.getValue(Constant.SERVER_OTA, false)).thenReturn("https://pet.example/ota/");
        when(params.getValue(Constant.SERVER_IP, false)).thenReturn("127.0.0.1");
        when(params.getValue(Constant.SERVER_PORT, false)).thenReturn("9000");
        when(params.getValue(Constant.SERVER_OTA_IP, false)).thenReturn("0.0.0.0");
        when(params.getValue(Constant.SERVER_OTA_PORT, false)).thenReturn("8002");
        when(templates.getDefaultTemplate()).thenReturn(template());
    }

    private void stubValidResources() {
        for (ModelConfigEntity model : List.of(
                model("llm-1", "LLM", "对话模型"), model("vllm-1", "VLLM", "视觉模型"),
                model("tts-1", "TTS", "语音模型"), model("asr-1", "ASR", "识别模型"),
                model("vad-1", "VAD", "检测模型"), model("memory-1", "Memory", "记忆模型"))) {
            when(models.selectById(model.getId())).thenReturn(model);
            when(models.getEnabledModelsByType(model.getModelType())).thenReturn(List.of(model));
        }
        TimbreEntity voice = new TimbreEntity();
        voice.setId("voice-1");
        voice.setTtsModelId("tts-1");
        when(timbres.selectById("voice-1")).thenReturn(voice);
    }

    private static AdminSystemSettingsSaveDTO validRequest() {
        AdminSystemSettingsSaveDTO request = new AdminSystemSettingsSaveDTO();
        request.setPublicWebsocketUrl("wss://pet.example/ws");
        request.setPublicOtaUrl("https://pet.example/ota/");
        request.setZixuanListenHost("127.0.0.1");
        request.setZixuanListenPort(8000);
        request.setOtaListenHost("0.0.0.0");
        request.setOtaListenPort(8002);
        request.setDefaultLlmModelId("llm-1");
        request.setDefaultVllmModelId("vllm-1");
        request.setDefaultTtsModelId("tts-1");
        request.setDefaultAsrModelId("asr-1");
        request.setDefaultVadModelId("vad-1");
        request.setDefaultMemoryModelId("memory-1");
        request.setDefaultTtsVoiceId("voice-1");
        request.setProactivePlannerPrompt("");
        return request;
    }

    private static AgentTemplateEntity template() {
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setLlmModelId("llm-1");
        template.setVllmModelId("vllm-1");
        template.setTtsModelId("tts-1");
        template.setAsrModelId("asr-1");
        template.setVadModelId("vad-1");
        template.setMemModelId("memory-1");
        template.setTtsVoiceId("voice-1");
        return template;
    }

    private static ModelConfigEntity model(String id, String type, String name) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setModelType(type);
        model.setModelName(name);
        model.setIsEnabled(1);
        return model;
    }
}
