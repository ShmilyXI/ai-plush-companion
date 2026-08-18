package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageDocument;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageValidator;

class SkillPackageValidatorTest {

    @Test
    void rejectsUnknownPluginToolAndInlineSecret() {
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        SkillPackageValidator validator = validator(plugins, mock(McpServerDao.class), mock(McpToolSnapshotDao.class),
                mock(DeviceToolSnapshotDao.class));
        Map<String, Object> manifest = baseManifest();
        manifest.put("apiKey", "secret-value");
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
        DeviceToolSnapshotDao devices = mock(DeviceToolSnapshotDao.class);
        when(devices.selectAvailableByToolName("set_volume")).thenReturn(List.of(new xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity()));

        SkillPackageValidator validator = validator(plugins, mock(McpServerDao.class), mock(McpToolSnapshotDao.class), devices);
        Map<String, Object> manifest = baseManifest();
        manifest.put("tools", List.of(
                tool("PLUGIN", "plugin-weather", "get_weather", true),
                tool("DEVICE_TOOL", "device-tool", "set_volume", false)));

        assertEquals("VALID", validator.validate(document(manifest), "skill-weather").getStatus());
    }

    private SkillPackageValidator validator(PluginDefinitionDao plugins, McpServerDao servers,
            McpToolSnapshotDao mcpTools, DeviceToolSnapshotDao devices) {
        return new SkillPackageValidator(plugins, servers, mcpTools, devices);
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
        manifest.put("runtime", Map.of("responseMode", "LLM", "timeoutMs", 30000));
        manifest.put("triggers", List.of(Map.of("type", "KEYWORD", "value", "weather")));
        manifest.put("secretRefs", List.of("weather_api_key"));
        return manifest;
    }

    private Map<String, Object> tool(String type, String ref, String name, boolean required) {
        return Map.of("type", type, "ref", ref, "name", name, "required", required);
    }
}
