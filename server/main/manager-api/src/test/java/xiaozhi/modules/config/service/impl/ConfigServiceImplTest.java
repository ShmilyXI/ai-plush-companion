package xiaozhi.modules.config.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import cn.hutool.json.JSONObject;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.service.AgentContextProviderService;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.companion.service.CompanionConfigService;
import xiaozhi.modules.companion.wakeword.service.DeviceWakeWordService;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.config.util.CompanionNamespace;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.sys.dto.SysParamsDTO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

class ConfigServiceImplTest {

    @Test
    void boundDeviceReceivesOnlyItsActiveWakeWord() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        DeviceWakeWordService wakeWordService = mock(DeviceWakeWordService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setMacAddress("9c:13:9e:8a:14:a4");
        device.setUserId(7L);
        device.setAgentId("agent-id");
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-id");
        agent.setUserId(7L);
        agent.setMemModelId(Constant.MEMORY_NO_MEM);
        when(deviceService.getDeviceByMacAddress(device.getMacAddress())).thenReturn(device);
        when(agentService.getAgentById("agent-id")).thenReturn(agent);
        when(wakeWordService.activeWords("device-id")).thenReturn(List.of("小布小布"));

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), mock(RedisUtils.class), deviceService, agentService,
                mock(CompanionConfigService.class), mock(ModelConfigService.class), wakeWordService);

        assertEquals(List.of("小布小布"),
                service.getAgentModels(device.getMacAddress(), new HashMap<>()).get("device_wakeup_words"));
    }

    @Test
    void migratedDeviceDoesNotReceiveLegacyAgentPluginConfiguration() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        AgentPluginMappingService legacyPlugins = mock(AgentPluginMappingService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setMacAddress("9c:13:9e:8a:14:a4");
        device.setUserId(7L);
        device.setAgentId("agent-id");
        device.setCapabilityConfigVersion(3L);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-id");
        agent.setUserId(7L);
        agent.setIntentModelId("Intent_function_call");
        agent.setMemModelId(Constant.MEMORY_NO_MEM);
        when(deviceService.getDeviceByMacAddress(device.getMacAddress())).thenReturn(device);
        when(agentService.getAgentById("agent-id")).thenReturn(agent);

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), mock(RedisUtils.class), deviceService, agentService,
                legacyPlugins);
        Map<String, Object> result = service.getAgentModels(device.getMacAddress(), new HashMap<>());

        assertFalse(result.containsKey("plugins"));
        verify(legacyPlugins, never()).agentPluginParamsByAgentId("agent-id");
    }

    @Test
    void evictsServerConfigurationCache() {
        RedisUtils redis = mock(RedisUtils.class);
        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), redis, mock(DeviceService.class), mock(AgentService.class));

        service.evictCache();

        verify(redis).delete(RedisKeys.getServerConfigKey());
    }

    @Test
    void cachedServerConfigIsCheckedAsAStringKeyedMapWithoutChangingNestedValues() {
        RedisUtils redisUtils = mock(RedisUtils.class);
        Map<String, Object> nested = new HashMap<>();
        nested.put("enabled", true);
        Map<String, Object> cached = new HashMap<>();
        cached.put("features", nested);
        when(redisUtils.get(RedisKeys.getServerConfigKey())).thenReturn(cached);

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), redisUtils, mock(DeviceService.class), mock(AgentService.class));

        Map<String, Object> result = service.getConfig(true);

        assertNotSame(cached, result);
        assertSame(nested, result.get("features"));
        assertEquals(true, ((Map<?, ?>) result.get("features")).get("enabled"));
    }

    @Test
    void nestedSystemParametersStillShareAndPopulateTheSameConfigBranch() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        SysParamsDTO enabled = parameter("server.features.enabled", "true", "boolean");
        SysParamsDTO labels = parameter("server.features.labels", "first;second", "array");
        when(sysParamsService.list(anyMap())).thenReturn(List.of(enabled, labels));
        ConfigServiceImpl service = newService(
                sysParamsService, mock(RedisUtils.class), mock(DeviceService.class), mock(AgentService.class));
        Map<String, Object> config = new HashMap<>();

        Object returned = ReflectionTestUtils.invokeMethod(service, "buildConfig", config);

        assertSame(config, returned);
        Map<?, ?> server = assertInstanceOf(Map.class, config.get("server"));
        Map<?, ?> features = assertInstanceOf(Map.class, server.get("features"));
        assertEquals(true, features.get("enabled"));
        assertEquals(List.of("first", "second"), features.get("labels"));
    }

    @Test
    void agentModelsKeepCompanionIdentitiesDistinctAndStablePerBoundDevice() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        DeviceEntity deviceA = new DeviceEntity();
        deviceA.setId("device-a");
        deviceA.setMacAddress("AA:BB:CC:DD:EE:01");
        deviceA.setUserId(7L);
        deviceA.setAgentId("agent-id");
        DeviceEntity deviceB = new DeviceEntity();
        deviceB.setId("device-b");
        deviceB.setMacAddress("AA:BB:CC:DD:EE:02");
        deviceB.setUserId(7L);
        deviceB.setAgentId("agent-id");
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-id");
        agent.setUserId(7L);
        agent.setMemModelId(Constant.MEMORY_NO_MEM);

        when(deviceService.getDeviceByMacAddress(deviceA.getMacAddress())).thenReturn(deviceA);
        when(deviceService.getDeviceByMacAddress(deviceB.getMacAddress())).thenReturn(deviceB);
        when(agentService.getAgentById("agent-id")).thenReturn(agent);

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), mock(RedisUtils.class), deviceService, agentService);
        Map<?, ?> identityA = companionIdentity(service, deviceA.getMacAddress());
        Map<?, ?> identityB = companionIdentity(service, deviceB.getMacAddress());
        Map<?, ?> repeatedIdentityA = companionIdentity(service, deviceA.getMacAddress());
        Map<?, ?> repeatedIdentityB = companionIdentity(service, deviceB.getMacAddress());

        assertEquals(7L, identityA.get("user_id"));
        assertEquals("agent-id", identityA.get("agent_id"));
        assertEquals("device-a", identityA.get("device_id"));
        assertEquals("device-b", identityB.get("device_id"));
        assertNotEquals(identityA.get("device_id"), identityB.get("device_id"));
        assertNotEquals(identityA.get("memory_namespace"), identityB.get("memory_namespace"));
        assertEquals(CompanionNamespace.create(7L, "agent-id", "device-a"), identityA.get("memory_namespace"));
        assertEquals(CompanionNamespace.create(7L, "agent-id", "device-b"), identityB.get("memory_namespace"));
        assertEquals(identityA, repeatedIdentityA);
        assertEquals(identityB, repeatedIdentityB);
    }

    @Test
    void boundDeviceReceivesActiveCompanionConfiguration() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        CompanionConfigService companionConfigService = mock(CompanionConfigService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setMacAddress("9c:13:9e:8a:14:a4");
        device.setUserId(7L);
        device.setAgentId("agent-id");
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-id");
        agent.setUserId(7L);
        agent.setMemModelId(Constant.MEMORY_NO_MEM);
        Map<String, Object> mapped = Map.of(
                "enabled", true,
                "relation_mode", "friend",
                "persona_prompt", "小智 initial prompt",
                "cue_files", Map.of("sigh", "sigh.wav"));

        when(deviceService.getDeviceByMacAddress(device.getMacAddress())).thenReturn(device);
        when(agentService.getAgentById("agent-id")).thenReturn(agent);
        when(companionConfigService.build(device, agent)).thenReturn(mapped);

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), mock(RedisUtils.class), deviceService, agentService,
                companionConfigService);
        Map<String, Object> result = service.getAgentModels(device.getMacAddress(), new HashMap<>());
        Map<?, ?> companion = assertInstanceOf(Map.class, result.get("companion"));

        assertEquals(true, companion.get("enabled"));
        assertEquals("friend", companion.get("relation_mode"));
        assertEquals("小智 initial prompt", companion.get("persona_prompt"));
        assertEquals(Map.of("sigh", "sigh.wav"), companion.get("cue_files"));
        assertSame(mapped, companion);
    }

    @Test
    void switchingActiveProfilesChangesTheMemoryNamespace() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setMacAddress("9c:13:9e:8a:14:a4");
        device.setUserId(7L);
        device.setAgentId("profile-a");
        AgentInfoVO profileA = new AgentInfoVO();
        profileA.setId("profile-a");
        profileA.setUserId(7L);
        profileA.setMemModelId(Constant.MEMORY_NO_MEM);
        AgentInfoVO profileB = new AgentInfoVO();
        profileB.setId("profile-b");
        profileB.setUserId(7L);
        profileB.setMemModelId(Constant.MEMORY_NO_MEM);

        when(deviceService.getDeviceByMacAddress(device.getMacAddress())).thenReturn(device);
        when(agentService.getAgentById("profile-a")).thenReturn(profileA);
        when(agentService.getAgentById("profile-b")).thenReturn(profileB);

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), mock(RedisUtils.class), deviceService, agentService);
        Map<?, ?> identityA = companionIdentity(service, device.getMacAddress());
        device.setAgentId("profile-b");
        Map<?, ?> identityB = companionIdentity(service, device.getMacAddress());

        assertEquals(CompanionNamespace.create(7L, "profile-a", "device-id"), identityA.get("memory_namespace"));
        assertEquals(CompanionNamespace.create(7L, "profile-b", "device-id"), identityB.get("memory_namespace"));
        assertNotEquals(identityA.get("memory_namespace"), identityB.get("memory_namespace"));
    }

    @Test
    void deviceRuntimeKeepsNativeAgentModelsWhenLegacyPrivateBindingsExist() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setMacAddress("9c:13:9e:8a:14:a4");
        device.setUserId(7L);
        device.setAgentId("agent-id");
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-id");
        agent.setUserId(7L);
        agent.setLlmModelId("LLM_DeepSeek");
        agent.setTtsModelId("TTS_EdgeTTS");
        agent.setMemModelId(Constant.MEMORY_NO_MEM);
        ModelConfigEntity llm = model("LLM_DeepSeek", Map.of("type", "openai"));
        ModelConfigEntity tts = model("TTS_EdgeTTS", Map.of("type", "edge"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:device_runtime_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE ai_companion_profile_model_binding (
                    id VARCHAR(32) PRIMARY KEY,
                    agent_id VARCHAR(32) NOT NULL,
                    model_type VARCHAR(16) NOT NULL,
                    source_type VARCHAR(16) NOT NULL,
                    resource_id VARCHAR(64) NOT NULL,
                    override_json VARCHAR(255)
                )
                """);
        jdbc.update("""
                INSERT INTO ai_companion_profile_model_binding
                    (id, agent_id, model_type, source_type, resource_id, override_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "binding-id", "agent-id", "LLM", "private", "legacy-llm",
                "{\"api_key\":\"private-secret\"}");
        LegacyBinding bindingBefore = legacyBinding(jdbc);

        when(deviceService.getDeviceByMacAddress(device.getMacAddress())).thenReturn(device);
        when(agentService.getAgentById("agent-id")).thenReturn(agent);
        when(modelConfigService.getModelByIdFromCache("LLM_DeepSeek")).thenReturn(llm);
        when(modelConfigService.getModelByIdFromCache("TTS_EdgeTTS")).thenReturn(tts);

        ConfigServiceImpl service = newService(
                mock(SysParamsService.class), mock(RedisUtils.class), deviceService, agentService,
                mock(CompanionConfigService.class), modelConfigService);
        Map<String, Object> result = service.getAgentModels(device.getMacAddress(), Map.of(
                "LLM", "LLM_DeepSeek",
                "TTS", "TTS_EdgeTTS"));
        Map<?, ?> selected = assertInstanceOf(Map.class, result.get("selected_module"));
        Map<?, ?> llmConfig = assertInstanceOf(Map.class, result.get("LLM"));
        Map<?, ?> ttsConfig = assertInstanceOf(Map.class, result.get("TTS"));

        assertEquals("LLM_DeepSeek", selected.get("LLM"));
        assertEquals("TTS_EdgeTTS", selected.get("TTS"));
        assertEquals(Map.of("type", "openai"), llmConfig.get("LLM_DeepSeek"));
        assertEquals(Map.of("type", "edge"), ttsConfig.get("TTS_EdgeTTS"));
        assertFalse(selected.values().stream().anyMatch(value -> String.valueOf(value).startsWith("private:")));
        assertFalse(llmConfig.keySet().stream().anyMatch(key -> String.valueOf(key).startsWith("private:")));
        assertFalse(ttsConfig.keySet().stream().anyMatch(key -> String.valueOf(key).startsWith("private:")));
        assertFalse(result.toString().contains("private:"));
        assertFalse(result.toString().contains("private-secret"));
        assertEquals(bindingBefore, legacyBinding(jdbc));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_companion_profile_model_binding WHERE id='binding-id'", Integer.class));
    }

    private static Map<?, ?> companionIdentity(ConfigServiceImpl service, String macAddress) {
        Map<String, Object> result = service.getAgentModels(macAddress, new HashMap<>());
        return assertInstanceOf(Map.class, result.get("companion_identity"));
    }

    private static SysParamsDTO parameter(String code, String value, String type) {
        SysParamsDTO parameter = new SysParamsDTO();
        parameter.setParamCode(code);
        parameter.setParamValue(value);
        parameter.setValueType(type);
        return parameter;
    }

    private static ModelConfigEntity model(String id, Map<String, Object> config) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setConfigJson(new JSONObject(config));
        return model;
    }

    private static LegacyBinding legacyBinding(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT id, agent_id, model_type, source_type, resource_id, override_json
                FROM ai_companion_profile_model_binding
                WHERE id='binding-id'
                """, (rs, rowNum) -> new LegacyBinding(
                rs.getString("id"),
                rs.getString("agent_id"),
                rs.getString("model_type"),
                rs.getString("source_type"),
                rs.getString("resource_id"),
                rs.getString("override_json")));
    }

    private record LegacyBinding(
            String id,
            String agentId,
            String modelType,
            String sourceType,
            String resourceId,
            String overrideJson) {
    }

    private static ConfigServiceImpl newService(
            SysParamsService sysParamsService,
            RedisUtils redisUtils,
            DeviceService deviceService,
            AgentService agentService) {
        return newService(sysParamsService, redisUtils, deviceService, agentService,
                mock(CompanionConfigService.class));
    }

    private static ConfigServiceImpl newService(
            SysParamsService sysParamsService,
            RedisUtils redisUtils,
            DeviceService deviceService,
            AgentService agentService,
            AgentPluginMappingService legacyPlugins) {
        return new ConfigServiceImpl(
                sysParamsService,
                deviceService,
                mock(ModelConfigService.class),
                agentService,
                mock(AgentTemplateService.class),
                redisUtils,
                mock(TimbreService.class),
                legacyPlugins,
                mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class),
                mock(VoiceCloneService.class),
                mock(AgentVoicePrintDao.class),
                mock(CorrectWordFileService.class),
                mock(CompanionConfigService.class),
                mock(DeviceWakeWordService.class));
    }

    private static ConfigServiceImpl newService(
            SysParamsService sysParamsService,
            RedisUtils redisUtils,
            DeviceService deviceService,
            AgentService agentService,
            CompanionConfigService companionConfigService) {
        return newService(sysParamsService, redisUtils, deviceService, agentService, companionConfigService,
                mock(ModelConfigService.class));
    }

    private static ConfigServiceImpl newService(
            SysParamsService sysParamsService,
            RedisUtils redisUtils,
            DeviceService deviceService,
            AgentService agentService,
            CompanionConfigService companionConfigService,
            ModelConfigService modelConfigService) {
        return new ConfigServiceImpl(
                sysParamsService,
                deviceService,
                modelConfigService,
                agentService,
                mock(AgentTemplateService.class),
                redisUtils,
                mock(TimbreService.class),
                mock(AgentPluginMappingService.class),
                mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class),
                mock(VoiceCloneService.class),
                mock(AgentVoicePrintDao.class),
                mock(CorrectWordFileService.class),
                companionConfigService,
                mock(DeviceWakeWordService.class));
    }

    private static ConfigServiceImpl newService(
            SysParamsService sysParamsService,
            RedisUtils redisUtils,
            DeviceService deviceService,
            AgentService agentService,
            CompanionConfigService companionConfigService,
            ModelConfigService modelConfigService,
            DeviceWakeWordService wakeWordService) {
        return new ConfigServiceImpl(
                sysParamsService, deviceService, modelConfigService, agentService,
                mock(AgentTemplateService.class), redisUtils, mock(TimbreService.class),
                mock(AgentPluginMappingService.class), mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class), mock(VoiceCloneService.class),
                mock(AgentVoicePrintDao.class), mock(CorrectWordFileService.class),
                companionConfigService, wakeWordService);
    }
}
