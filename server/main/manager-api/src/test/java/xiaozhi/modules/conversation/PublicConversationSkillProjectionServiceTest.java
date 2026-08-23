package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.conversation.service.impl.PublicConversationSkillProjectionServiceImpl;

class PublicConversationSkillProjectionServiceTest {
    @Test
    void projectsPublishedPromptAndTriggersWithoutExecutableTools() {
        AgentVersionSkillBindingDao bindings = mock(AgentVersionSkillBindingDao.class);
        CapabilityDao capabilities = mock(CapabilityDao.class);
        SkillPackageService packages = mock(SkillPackageService.class);
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
        packageRow.setManifestJson("{\"triggers\":[{\"type\":\"KEYWORD\",\"value\":\"天气\"}],\"timeoutMs\":12000}");
        when(packages.selectVersion("skill-weather", 2)).thenReturn(packageRow);

        var result = new PublicConversationSkillProjectionServiceImpl(bindings, capabilities, packages)
                .project("agent-a", 4);

        assertEquals(1, result.size());
        assertEquals("先确认城市，再回答天气。", result.get(0).get("executionPrompt"));
        assertEquals(List.of(), result.get(0).get("toolNames"));
        assertEquals(0, ((java.util.Map<?, ?>) ((List<?>) result.get(0).get("triggers")).get(0)).get("priority"));
        assertTrue(result.get(0).containsKey("packageSha256"));
    }
}
