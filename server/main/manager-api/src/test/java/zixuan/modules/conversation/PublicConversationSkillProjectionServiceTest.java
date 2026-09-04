package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zixuan.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import zixuan.modules.companion.capability.dao.CapabilityDao;
import zixuan.modules.companion.capability.dao.PluginDefinitionDao;
import zixuan.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import zixuan.modules.companion.capability.entity.CapabilityEntity;
import zixuan.modules.companion.capability.entity.SkillPackageEntity;
import zixuan.modules.companion.capability.entity.PluginDefinitionEntity;
import zixuan.modules.companion.capability.service.SkillPackageService;
import zixuan.modules.conversation.service.impl.PublicConversationSkillProjectionServiceImpl;

class PublicConversationSkillProjectionServiceTest {
    @Test
    void projectsPublishedWeatherToolWithSchemaAndDefaults() {
        AgentVersionSkillBindingDao bindings = mock(AgentVersionSkillBindingDao.class);
        CapabilityDao capabilities = mock(CapabilityDao.class);
        SkillPackageService packages = mock(SkillPackageService.class);
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        AgentVersionSkillBindingEntity binding = new AgentVersionSkillBindingEntity();
        binding.setSkillId("skill-weather");
        binding.setAgentId("agent-a");
        binding.setVersionNo(4);
        binding.setVersionMode("LATEST");
        binding.setTriggerPriority(7);
        when(bindings.selectEnabledByAgentVersion("agent-a", 4)).thenReturn(List.of(binding));
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("skill-weather");
        capability.setType("SKILL");
        capability.setStatus("PUBLISHED");
        capability.setPublishedVersion(2);
        capability.setName("天气");
        capability.setDescription("天气查询");
        when(capabilities.selectById("skill-weather")).thenReturn(capability);
        SkillPackageEntity packageRow = new SkillPackageEntity();
        packageRow.setVersionNo(2);
        packageRow.setPublished(1);
        packageRow.setPackageSha256("a".repeat(64));
        packageRow.setSkillMarkdown("先确认城市，再回答天气。");
        packageRow.setManifestJson("{\"triggers\":[{\"type\":\"KEYWORD\",\"value\":\"天气\"}],"
                + "\"timeoutMs\":12000,\"tools\":[{\"type\":\"PLUGIN\",\"ref\":\"plugin-weather\","
                + "\"name\":\"get_weather\",\"required\":true,\"defaults\":{\"location\":\"深圳\","
                + "\"api_key_secret_id\":\"secret-ref\"}}]}");
        when(packages.selectVersion("skill-weather", 2)).thenReturn(packageRow);
        CapabilityEntity pluginCapability = new CapabilityEntity();
        pluginCapability.setId("plugin-weather");
        pluginCapability.setType("PLUGIN");
        pluginCapability.setStatus("PUBLISHED");
        pluginCapability.setDescription("服务端天气查询执行器");
        when(capabilities.selectById("plugin-weather")).thenReturn(pluginCapability);
        PluginDefinitionEntity plugin = new PluginDefinitionEntity();
        plugin.setCapabilityId("plugin-weather");
        plugin.setExecutorName("get_weather");
        plugin.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}}}");
        when(plugins.selectByCapabilityId("plugin-weather")).thenReturn(plugin);

        var result = new PublicConversationSkillProjectionServiceImpl(bindings, capabilities, packages, plugins)
                .project("agent-a", 4);

        assertEquals(1, result.skills().size());
        assertEquals("先确认城市，再回答天气。", result.skills().get(0).get("executionPrompt"));
        assertEquals(List.of("get_weather"), result.skills().get(0).get("toolNames"));
        assertEquals(0, ((java.util.Map<?, ?>) ((List<?>) result.skills().get(0).get("triggers")).get(0)).get("priority"));
        assertTrue(result.skills().get(0).containsKey("packageSha256"));
        assertEquals("PLUGIN", result.tools().get("get_weather").get("type"));
        assertEquals("plugin-weather", result.tools().get("get_weather").get("refId"));
        assertEquals(Map.of("location", "深圳"), result.tools().get("get_weather").get("defaults"));
        assertTrue(((Map<?, ?>) result.tools().get("get_weather").get("runtime")).containsKey("schema"));
    }

    @Test
    void rejectsPluginOutsidePublicReadonlyAllowlist() {
        AgentVersionSkillBindingDao bindings = mock(AgentVersionSkillBindingDao.class);
        CapabilityDao capabilities = mock(CapabilityDao.class);
        SkillPackageService packages = mock(SkillPackageService.class);
        PluginDefinitionDao plugins = mock(PluginDefinitionDao.class);
        AgentVersionSkillBindingEntity binding = new AgentVersionSkillBindingEntity();
        binding.setSkillId("skill-web-search");
        binding.setVersionMode("LATEST");
        when(bindings.selectEnabledByAgentVersion("agent-a", 4)).thenReturn(List.of(binding));
        CapabilityEntity skill = new CapabilityEntity();
        skill.setId("skill-web-search");
        skill.setType("SKILL");
        skill.setStatus("PUBLISHED");
        skill.setPublishedVersion(1);
        skill.setName("联网搜索");
        when(capabilities.selectById("skill-web-search")).thenReturn(skill);
        SkillPackageEntity packageRow = new SkillPackageEntity();
        packageRow.setVersionNo(1);
        packageRow.setPublished(1);
        packageRow.setPackageSha256("b".repeat(64));
        packageRow.setSkillMarkdown("调用搜索工具");
        packageRow.setManifestJson("{\"tools\":[{\"type\":\"PLUGIN\",\"ref\":\"plugin-web-search\","
                + "\"name\":\"web_search\"}],\"triggers\":[]}");
        when(packages.selectVersion("skill-web-search", 1)).thenReturn(packageRow);

        var result = new PublicConversationSkillProjectionServiceImpl(bindings, capabilities, packages, plugins)
                .project("agent-a", 4);

        assertTrue(result.tools().isEmpty());
        assertEquals(List.of(), result.skills().get(0).get("toolNames"));
    }
}
