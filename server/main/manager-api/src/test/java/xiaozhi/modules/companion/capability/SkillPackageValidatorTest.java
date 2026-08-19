package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageDocument;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageValidator;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;

class SkillPackageValidatorTest {

    @Test
    void rejectsUnknownPluginToolAndInlineSecret() {
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        SkillPackageValidator validator = validator(plugins, mock(McpServerDao.class), mock(McpToolSnapshotDao.class),
                mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("x-api-key", "secret-value");
        manifest.put("tools", List.of(tool("PLUGIN", "missing-plugin", "get_weather", true)));

        var report = validator.validate(document(manifest), null);

        assertEquals("INVALID", report.getStatus());
        assertEquals(List.of("UNKNOWN_TOOL", "INLINE_SECRET"), report.errorCodes());
    }

    @Test
    void acceptsApprovedPluginMcpAndDeviceToolReferences() {
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        PluginDefinitionEntity plugin = new PluginDefinitionEntity();
        plugin.setCapabilityId("plugin-weather");
        plugin.setExecutorName("get_weather");
        when(plugins.selectByCapabilityId("plugin-weather")).thenReturn(plugin);
        CapabilityDao capabilities = mock(CapabilityDao.class);
        CapabilityEntity pluginCapability = new CapabilityEntity();
        pluginCapability.setId("plugin-weather");
        pluginCapability.setType("PLUGIN");
        pluginCapability.setStatus("PUBLISHED");
        when(capabilities.selectById("plugin-weather")).thenReturn(pluginCapability);
        DeviceToolSnapshotDao devices = mock(DeviceToolSnapshotDao.class);
        when(devices.selectAvailableByToolName("set_volume")).thenReturn(List.of(new xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity()));

        SkillPackageValidator validator = validator(plugins, mock(McpServerDao.class), mock(McpToolSnapshotDao.class), devices,
                capabilities);
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(
                tool("PLUGIN", "plugin-weather", "get_weather", true),
                tool("DEVICE_TOOL", "device-tool", "set_volume", false)));

        assertEquals("VALID", validator.validate(document(manifest), "skill-weather").getStatus());
    }

    @Test
    void rejectsMcpDefaultsThatDoNotMatchTheRegisteredSchema() {
        McpToolSnapshotDao tools = mock(McpToolSnapshotDao.class);
        McpToolSnapshotEntity tool = new McpToolSnapshotEntity();
        tool.setId("snapshot-search");
        tool.setMcpServerId("mcp-search");
        tool.setToolName("web_search");
        tool.setApproved(1);
        tool.setStatus("ACTIVE");
        tool.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"minimum\":1}}}");
        when(tools.selectById("snapshot-search")).thenReturn(tool);

        McpServerDao servers = mock(McpServerDao.class);
        McpServerEntity server = new McpServerEntity();
        server.setId("mcp-search");
        server.setCapabilityId("capability-search");
        when(servers.selectById("mcp-search")).thenReturn(server);

        CapabilityDao capabilities = mock(CapabilityDao.class);
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("capability-search");
        capability.setType("MCP_SERVER");
        capability.setStatus("PUBLISHED");
        when(capabilities.selectById("capability-search")).thenReturn(capability);

        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), servers, tools,
                mock(DeviceToolSnapshotDao.class), capabilities);
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(Map.of(
                "type", "MCP", "ref", "snapshot-search", "name", "web_search",
                "required", true, "defaults", Map.of("limit", "ten"))));

        var report = validator.validate(document(manifest), "skill-weather");

        assertTrue(report.errorCodes().contains("INVALID_TOOL_DEFAULT_VALUE"));
    }

    @Test
    void rejectsToolsFromAnUnpublishedPlugin() {
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        PluginDefinitionEntity plugin = new PluginDefinitionEntity();
        plugin.setCapabilityId("plugin-weather");
        plugin.setExecutorName("get_weather");
        when(plugins.selectByCapabilityId("plugin-weather")).thenReturn(plugin);
        CapabilityDao capabilities = mock(CapabilityDao.class);
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("plugin-weather");
        capability.setType("PLUGIN");
        capability.setStatus("DRAFT");
        when(capabilities.selectById("plugin-weather")).thenReturn(capability);
        SkillPackageValidator validator = validator(plugins, mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class), capabilities);
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(tool("PLUGIN", "plugin-weather", "get_weather", true)));

        assertTrue(validator.validate(document(manifest), "skill-weather")
                .errorCodes().contains("UNKNOWN_TOOL"));
    }

    @Test
    void rejectsPackagesWithoutRuntimeAndTriggers() {
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        PluginDefinitionEntity plugin = new PluginDefinitionEntity();
        plugin.setCapabilityId("plugin-weather");
        plugin.setExecutorName("get_weather");
        when(plugins.selectByCapabilityId("plugin-weather")).thenReturn(plugin);
        SkillPackageValidator validator = validator(plugins, mock(McpServerDao.class), mock(McpToolSnapshotDao.class),
                mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.remove("runtime");
        manifest.remove("triggers");
        manifest.put("tools", List.of());

        var report = validator.validate(document(manifest), "skill-weather");

        assertEquals(List.of("MISSING_RUNTIME", "MISSING_TRIGGERS"), report.errorCodes());
    }

    @Test
    void rejectsRuntimeThatCannotBeProjectedToThePythonBundle() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("runtime", Map.of("responseMode", "LLM", "timeoutMs", "30000"));

        var report = validator.validate(document(manifest), "skill-weather");

        assertTrue(report.errorCodes().contains("MISSING_RUNTIME_SEMANTIC_THRESHOLD"));
        assertTrue(report.errorCodes().contains("INVALID_RUNTIME_TIMEOUT"));
    }

    @Test
    void rejectsRuntimeAndToolValuesThatCannotBeProjectedToThePythonBundle() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("runtime", Map.of("responseMode", "LLM", "timeoutMs", 30000,
                "semanticThreshold", 0.7, "failureMessage", 42));
        manifest.put("tools", List.of(Map.of("type", "DEVICE_TOOL", "ref", "device-tool",
                "name", "set_volume", "alias", 42, "purpose", List.of("volume"),
                "defaults", "not-an-object", "required", "yes")));

        var report = validator.validate(document(manifest), "skill-weather");

        assertTrue(report.errorCodes().contains("INVALID_RUNTIME_FAILURE_MESSAGE"));
        assertTrue(report.errorCodes().contains("INVALID_TOOL_ALIAS"));
        assertTrue(report.errorCodes().contains("INVALID_TOOL_PURPOSE"));
        assertTrue(report.errorCodes().contains("INVALID_TOOL_DEFAULTS"));
        assertTrue(report.errorCodes().contains("INVALID_TOOL_REQUIRED"));
    }

    @Test
    void rejectsTriggerThatCannotBeParsedByThePythonBundle() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("triggers", List.of(Map.of("type", "KEYWORD", "value", "天气")));

        var report = validator.validate(document(manifest), "skill-weather");

        assertTrue(report.errorCodes().contains("INVALID_TRIGGER_PRIORITY"));
    }

    @Test
    void rejectsInvalidRegexTriggersAtThePackageBoundary() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("triggers", List.of(Map.of("type", "REGEX", "value", "[", "priority", 0)));

        var report = validator.validate(document(manifest), "skill-weather");

        assertTrue(report.errorCodes().contains("INVALID_TRIGGER_REGEX"));
    }

    @Test
    void validatesDeviceRequirementShape() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of());
        manifest.put("deviceRequirements", List.of(
                Map.of("type", "DEVICE_MODEL", "value", "zhengchen-cam"),
                Map.of("type", "MIN_FIRMWARE_VERSION", "value", "1.2.0")));

        assertEquals("VALID", validator.validate(document(manifest), "skill-weather").getStatus());

        manifest.put("deviceRequirements", List.of(Map.of("type", "UNKNOWN", "value", "x")));
        assertTrue(validator.validate(document(manifest), "skill-weather")
                .errorCodes().contains("INVALID_DEVICE_REQUIREMENT_TYPE"));
    }

    @Test
    void acceptsARegisteredRoleMcpReference() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        AgentMcpAccessPointService registry = mock(AgentMcpAccessPointService.class);
        when(registry.getAgentMcpToolsListStrict("agent-weather")).thenReturn(List.of("get_weather"));
        validator.setAgentMcpAccessPointService(registry);
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(tool("ROLE_MCP", "agent-weather", "get_weather", true)));

        var report = validator.validate(document(manifest), "skill-weather");

        assertEquals("VALID", report.getStatus());
    }

    @Test
    void rejectsRoleMcpWhenTheRegistryIsUnavailable() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(tool("ROLE_MCP", "agent-weather", "get_weather", true)));

        var report = validator.validate(document(manifest), "skill-weather");

        assertEquals(List.of("UNKNOWN_TOOL"), report.errorCodes());
    }

    @Test
    void rejectsRoleMcpToolMissingFromTheAgentRegistry() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        AgentMcpAccessPointService registry = mock(AgentMcpAccessPointService.class);
        when(registry.getAgentMcpToolsListStrict("agent-weather")).thenReturn(List.of("get_time"));
        validator.setAgentMcpAccessPointService(registry);
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(tool("ROLE_MCP", "agent-weather", "get_weather", true)));

        var report = validator.validate(document(manifest), "skill-weather");

        assertEquals(List.of("UNKNOWN_TOOL"), report.errorCodes());
    }

    @Test
    void treatsToolsAsRequiredUnlessExplicitlyOptional() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(Map.of(
                "type", "PLUGIN", "ref", "missing-plugin", "name", "get_weather")));

        var required = validator.validate(document(manifest), "skill-weather");
        assertEquals(List.of("UNKNOWN_TOOL"), required.errorCodes());

        manifest.put("tools", List.of(tool("PLUGIN", "missing-plugin", "get_weather", false)));
        var optional = validator.validate(document(manifest), "skill-weather");
        assertEquals("VALID", optional.getStatus());
        assertEquals(List.of(), optional.errorCodes());
    }

    @Test
    void rejectsUnknownNestedFieldsAndRuntimeConnectionConfiguration() {
        SkillPackageValidator validator = validator(mock(PluginDefinitionDao.class), mock(McpServerDao.class),
                mock(McpToolSnapshotDao.class), mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("runtime", Map.of("responseMode", "LLM", "timeoutMs", 30000,
                "semanticThreshold", 0.7, "typoTimeout", 10));
        manifest.put("triggers", List.of(Map.of("type", "KEYWORD", "value", "天气", "priority", 0,
                "unexpected", true)));
        manifest.put("tools", List.of(Map.of("type", "PLUGIN", "ref", "missing", "name", "get_weather",
                "required", false, "defaults", Map.of("connectionConfig", Map.of("url", "https://example.test")))));

        var report = validator.validate(document(manifest), "skill-weather");

        assertTrue(report.errorCodes().contains("INVALID_RUNTIME_FIELD"));
        assertTrue(report.errorCodes().contains("INVALID_TRIGGER_FIELD"));
        assertTrue(report.errorCodes().contains("FORBIDDEN_CONFIGURATION"));
    }

    private SkillPackageValidator validator(PluginDefinitionDao plugins, McpServerDao servers,
            McpToolSnapshotDao mcpTools, DeviceToolSnapshotDao devices) {
        return validator(plugins, servers, mcpTools, devices, mock(CapabilityDao.class));
    }

    private SkillPackageValidator validator(PluginDefinitionDao plugins, McpServerDao servers,
            McpToolSnapshotDao mcpTools, DeviceToolSnapshotDao devices, CapabilityDao capabilities) {
        return new SkillPackageValidator(plugins, servers, mcpTools, devices, capabilities);
    }

    private SkillPackageDocument document(Map<String, Object> manifest) {
        return new SkillPackageDocument(manifest, "# Weather\n", Map.of(), "a".repeat(64), 1);
    }

    private Map<String, Object> baseManifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "skill-weather");
        manifest.put("name", "Weather");
        manifest.put("version", 1);
        manifest.put("runtime", Map.of("responseMode", "LLM", "timeoutMs", 30000, "semanticThreshold", 0.7));
        manifest.put("triggers", List.of(Map.of("type", "KEYWORD", "value", "weather", "priority", 0)));
        manifest.put("secretRefs", List.of("weather_api_key"));
        return manifest;
    }

    private Map<String, Object> tool(String type, String ref, String name, boolean required) {
        return Map.of("type", type, "ref", ref, "name", name, "required", required);
    }
}
