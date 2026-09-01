package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.service.impl.CompanionConfigServiceImpl;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.sys.service.SysParamsService;

class CompanionConfigServiceImplTest {

    private final SysParamsService params = Mockito.mock(SysParamsService.class);
    private final CompanionConfigService service = new CompanionConfigServiceImpl(params);

    @Test
    void mapsActiveProfileFieldsToDeviceConfiguration() {
        DeviceEntity device = new DeviceEntity();
        AgentEntity agent = new AgentEntity();
        agent.setCompanionEnabled(1);
        agent.setSystemPrompt("小智 initial prompt");
        agent.setRelationMode("friend");
        agent.setUserAddress("小夏");
        agent.setCompanionCueConfig("{\"sigh\":\"sigh.wav\"}");
        agent.setScreenExpressionEnabled(1);
        agent.setCameraPreferenceEnabled(0);

        Map<String, Object> result = service.build(device, agent);

        assertEquals(true, result.get("enabled"));
        assertEquals("小智 initial prompt", result.get("persona_prompt"));
        assertEquals("friend", result.get("relation_mode"));
        assertEquals("小夏", result.get("user_address"));
        assertEquals(Map.of("sigh", "sigh.wav"), result.get("cue_files"));
        assertEquals(true, result.get("screen_expression_enabled"));
        assertEquals(false, result.get("camera_preference_enabled"));
    }

    @Test
    void mapsDeviceModeAndGlobalPlannerPromptWithTurnBasedFallback() {
        DeviceEntity device = new DeviceEntity();
        device.setCompanionMode("proactive");
        AgentEntity agent = new AgentEntity();
        agent.setCompanionEnabled(1);
        Mockito.when(params.getValue("companion.proactive_planner_prompt", false))
                .thenReturn("只在有自然切入点时主动陪伴");

        Map<String, Object> result = service.build(device, agent);

        assertEquals("proactive", result.get("mode"));
        assertEquals("只在有自然切入点时主动陪伴", result.get("proactive_planner_prompt"));
    }

    @Test
    void invalidDeviceModeFallsBackToTurnBased() {
        DeviceEntity device = new DeviceEntity();
        device.setCompanionMode("invalid");
        AgentEntity agent = new AgentEntity();

        Map<String, Object> result = service.build(device, agent);

        assertEquals("turn_based", result.get("mode"));
    }

    @Test
    void invalidOrEmptyCueConfigurationFallsBackToAnEmptyMap() {
        AgentEntity invalid = new AgentEntity();
        invalid.setCompanionCueConfig("not-json");
        AgentEntity empty = new AgentEntity();
        empty.setCompanionCueConfig("  ");

        assertEquals(Map.of(), service.build(new DeviceEntity(), invalid).get("cue_files"));
        assertEquals(Map.of(), service.build(new DeviceEntity(), empty).get("cue_files"));
    }

    @Test
    void cueConfigurationKeepsOnlySupportedStringEntriesWithSafeRelativePaths() {
        AgentEntity agent = new AgentEntity();
        agent.setCompanionCueConfig("""
                {
                  "sigh": "config/assets/companion/sigh.wav",
                  "laugh": "../secret.wav",
                  "hesitate": "/tmp/hesitate.wav",
                  "breathe": "file:/tmp/breathe.wav",
                  "idle": "idle.wav"
                }
                """);

        Map<?, ?> cueFiles = (Map<?, ?>) service.build(new DeviceEntity(), agent).get("cue_files");

        assertEquals(Map.of("sigh", "config/assets/companion/sigh.wav"), cueFiles);
    }

    @Test
    void nonObjectCueConfigurationIsEmpty() {
        for (String rawConfig : new String[] { "[]", "true", "42", "null" }) {
            AgentEntity agent = new AgentEntity();
            agent.setCompanionCueConfig(rawConfig);

            assertEquals(Map.of(), service.build(new DeviceEntity(), agent).get("cue_files"));
        }
    }

    @Test
    void cueConfigurationRejectsEveryNonStringValueType() {
        for (String rawConfig : new String[] {
                "{\"sigh\": true}",
                "{\"sigh\": 1}",
                "{\"sigh\": {\"path\": \"sigh.wav\"}}",
                "{\"sigh\": null}",
                "{\"sigh\": [\"sigh.wav\"]}"
        }) {
            AgentEntity agent = new AgentEntity();
            agent.setCompanionCueConfig(rawConfig);

            assertEquals(Map.of(), service.build(new DeviceEntity(), agent).get("cue_files"));
        }
    }
}
