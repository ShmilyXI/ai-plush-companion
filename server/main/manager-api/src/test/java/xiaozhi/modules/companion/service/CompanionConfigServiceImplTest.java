package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.service.impl.CompanionConfigServiceImpl;
import xiaozhi.modules.device.entity.DeviceEntity;

class CompanionConfigServiceImplTest {

    private final CompanionConfigService service = new CompanionConfigServiceImpl();

    @Test
    void mapsActiveProfileFieldsToDeviceConfiguration() {
        DeviceEntity device = new DeviceEntity();
        AgentEntity agent = new AgentEntity();
        agent.setId("profile-a");
        agent.setUserId(7L);
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
        assertEquals("companion:7:profile-a", result.get("profile_memory_namespace"));
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
