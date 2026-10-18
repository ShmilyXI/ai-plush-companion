package zixuan.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.service.impl.CompanionConfigServiceImpl;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.sys.service.SysParamsService;

class CompanionConfigServiceImplTest {

    private final SysParamsService params = Mockito.mock(SysParamsService.class);
    private final CompanionConfigService service = new CompanionConfigServiceImpl(params);

    @Test
    void mapsActiveProfileFieldsToDeviceConfiguration() {
        DeviceEntity device = new DeviceEntity();
        AgentEntity agent = new AgentEntity();
        agent.setCompanionEnabled(1);
        agent.setSystemPrompt("紫萱 initial prompt");
        agent.setRelationMode("friend");
        agent.setUserAddress("小夏");
        agent.setScreenExpressionEnabled(1);
        agent.setCameraPreferenceEnabled(0);

        Map<String, Object> result = service.build(device, agent);

        assertEquals(true, result.get("enabled"));
        assertEquals("紫萱 initial prompt", result.get("persona_prompt"));
        assertEquals("friend", result.get("relation_mode"));
        assertEquals("小夏", result.get("user_address"));
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
    void nullScreenExpressionFlagDefaultsToEnabled() {
        DeviceEntity device = new DeviceEntity();
        AgentEntity agent = new AgentEntity();
        agent.setCompanionEnabled(0);
        agent.setScreenExpressionEnabled(null);

        Map<String, Object> result = service.build(device, agent);

        assertEquals(false, result.get("enabled"));
        assertEquals(true, result.get("screen_expression_enabled"));
    }

    @Test
    void zeroScreenExpressionFlagDisablesExpression() {
        DeviceEntity device = new DeviceEntity();
        AgentEntity agent = new AgentEntity();
        agent.setCompanionEnabled(1);
        agent.setScreenExpressionEnabled(0);

        Map<String, Object> result = service.build(device, agent);

        assertEquals(true, result.get("enabled"));
        assertEquals(false, result.get("screen_expression_enabled"));
    }




}
