package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilitySecretDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.dao.DeviceToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.McpServerDao;
import xiaozhi.modules.companion.capability.dao.McpToolSnapshotDao;
import xiaozhi.modules.companion.capability.dao.PluginDefinitionDao;
import xiaozhi.modules.companion.capability.dao.SkillDefinitionDao;
import xiaozhi.modules.companion.capability.dao.SkillToolMappingDao;
import xiaozhi.modules.companion.capability.dao.SkillTriggerDao;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.McpServerDTO;
import xiaozhi.modules.companion.capability.dto.SkillToolDTO;
import xiaozhi.modules.companion.capability.dto.SkillTriggerDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.SkillDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.SkillToolMappingEntity;
import xiaozhi.modules.companion.capability.entity.SkillTriggerEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.companion.capability.vo.SkillPackageImportVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.service.impl.CapabilityServiceImpl;
import xiaozhi.modules.companion.service.CompanionAuditService;

class CapabilityServiceImplTest {
    private final CapabilityDao capabilityDao = mock(CapabilityDao.class);
    private final CapabilityVersionDao versionDao = mock(CapabilityVersionDao.class);
    private final SkillDefinitionDao skillDefinitionDao = mock(SkillDefinitionDao.class);
    private final SkillTriggerDao triggerDao = mock(SkillTriggerDao.class);
    private final SkillToolMappingDao toolMappingDao = mock(SkillToolMappingDao.class);
    private final DeviceSkillMappingDao deviceSkillMappingDao = mock(DeviceSkillMappingDao.class);
    private final PluginDefinitionDao pluginDao = mock(PluginDefinitionDao.class);
    private final McpServerDao mcpServerDao = mock(McpServerDao.class);
    private final McpToolSnapshotDao mcpToolDao = mock(McpToolSnapshotDao.class);
    private final DeviceToolSnapshotDao deviceToolDao = mock(DeviceToolSnapshotDao.class);
    private final CapabilitySecretDao secretDao = mock(CapabilitySecretDao.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final CapabilityServiceImpl service = new CapabilityServiceImpl(
            capabilityDao, versionDao, skillDefinitionDao, triggerDao, toolMappingDao,
            deviceSkillMappingDao, pluginDao, mcpServerDao, mcpToolDao, deviceToolDao,
            secretDao, audit);

    @BeforeEach
    void acceptWrites() {
        when(capabilityDao.insert(any(CapabilityEntity.class))).thenReturn(1);
        when(capabilityDao.updateById(any(CapabilityEntity.class))).thenReturn(1);
        when(versionDao.insert(any(CapabilityVersionEntity.class))).thenReturn(1);
        when(skillDefinitionDao.insert(any(SkillDefinitionEntity.class))).thenReturn(1);
        when(skillDefinitionDao.updateById(any(SkillDefinitionEntity.class))).thenReturn(1);
        when(triggerDao.insert(any(SkillTriggerEntity.class))).thenReturn(1);
        when(toolMappingDao.insert(any(SkillToolMappingEntity.class))).thenReturn(1);
        when(pluginDao.insert(any(PluginDefinitionEntity.class))).thenReturn(1);
        when(pluginDao.updateById(any(PluginDefinitionEntity.class))).thenReturn(1);
        when(mcpServerDao.insert(any(xiaozhi.modules.companion.capability.entity.McpServerEntity.class))).thenReturn(1);
        when(mcpServerDao.updateById(any(xiaozhi.modules.companion.capability.entity.McpServerEntity.class))).thenReturn(1);
    }

    @Test
    void createsSkillDraftFromExistingPluginTool() {
        stubWeatherPlugin();

        var result = service.create(42L, weatherSkill("帮用户查询天气"));

        assertEquals("SKILL", result.getType());
        assertEquals("DRAFT", result.getStatus());
        assertEquals("帮用户查询天气", result.getExecutionPrompt());
        assertEquals(1, result.getTriggers().size());
        assertEquals(1, result.getTools().size());
        verify(capabilityDao).insert(any(CapabilityEntity.class));
        verify(skillDefinitionDao).insert(any(SkillDefinitionEntity.class));
        verify(triggerDao).insert(any(SkillTriggerEntity.class));
        verify(toolMappingDao).insert(any(SkillToolMappingEntity.class));
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skillCreatePersistsCanonicalPackageBeforeProjection() {
        stubWeatherPlugin();
        SkillPackageService packages = mock(SkillPackageService.class);
        service.setSkillPackageService(packages);

        service.create(42L, weatherSkill("包内执行说明"));

        var id = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(packages).saveOnlineDraft(org.mockito.ArgumentMatchers.eq(42L), id.capture(),
                org.mockito.ArgumentMatchers.eq(1), any(CapabilitySaveDTO.class));
        assertTrue(!id.getValue().isBlank());
        verify(skillDefinitionDao).insert(any(SkillDefinitionEntity.class));
    }

    @Test
    void skillDetailsExposeCurrentPackageMetadata() {
        CapabilityEntity capability = skillCapability("skill-1");
        when(capabilityDao.selectById("skill-1")).thenReturn(capability);
        SkillPackageEntity draft = new SkillPackageEntity();
        draft.setCapabilityId("skill-1");
        draft.setVersionNo(3);
        draft.setPackageSha256("a".repeat(64));
        draft.setSourceType("UPLOAD");
        draft.setValidationStatus("VALID");
        SkillPackageService packages = mock(SkillPackageService.class);
        when(packages.selectDraft("skill-1")).thenReturn(draft);
        service.setSkillPackageService(packages);

        var result = service.get("skill-1");

        assertEquals(3, result.getPackageVersion());
        assertEquals("a".repeat(64), result.getPackageSha256());
        assertEquals("UPLOAD", result.getPackageSource());
    }

    @Test
    void uploadedPackageRefreshesTheSkillProjection() {
        stubWeatherPlugin();
        CapabilityEntity capability = skillCapability("skill-1");
        when(capabilityDao.selectForUpdate("skill-1")).thenReturn(capability);
        SkillPackageService packages = mock(SkillPackageService.class);
        SkillPackageValidationVO validation = new SkillPackageValidationVO();
        validation.setStatus("VALID");
        SkillPackageImportVO inspected = new SkillPackageImportVO();
        inspected.setCapabilityId("skill-1");
        inspected.setVersion(2);
        inspected.setValidation(validation);
        MockMultipartFile file = new MockMultipartFile("file", "skill.skill.zip", "application/zip", new byte[] { 1 });
        when(packages.inspect(file)).thenReturn(inspected);
        SkillPackageEntity row = new SkillPackageEntity();
        row.setCapabilityId("skill-1");
        row.setVersionNo(2);
        row.setPackageSha256("b".repeat(64));
        row.setSourceType("UPLOAD");
        row.setValidationStatus("VALID");
        row.setSkillMarkdown("包内天气说明");
        row.setManifestJson(JsonUtils.toJsonString(Map.of(
                "id", "skill-1", "name", "包天气", "version", 2,
                "runtime", Map.of("responseMode", "LLM", "timeoutMs", 30000, "semanticThreshold", 0.7),
                "triggers", List.of(Map.of("type", "KEYWORD", "value", "天气")),
                "tools", List.of(Map.of("type", "PLUGIN", "ref", "plugin-weather", "name", "get_weather",
                        "required", true)))));
        when(packages.selectVersion("skill-1", 2)).thenReturn(null, row);
        service.setSkillPackageService(packages);

        var result = service.savePackage(42L, "skill-1", file);

        assertEquals("包天气", result.getName());
        assertEquals("包内天气说明", result.getExecutionPrompt());
        assertEquals(2, result.getPackageVersion());
        verify(packages).saveUploadedDraft(42L, "skill-1", file);
        verify(toolMappingDao).insert(any(SkillToolMappingEntity.class));
    }

    @Test
    void rejectsUnsupportedTypesAndExecutableFields() {
        CapabilitySaveDTO unsupported = weatherSkill("prompt");
        unsupported.setType("PYTHON");
        assertThrows(RenException.class, () -> service.create(42L, unsupported));

        CapabilitySaveDTO executable = weatherSkill("prompt");
        executable.captureUnknown("code", "print('unsafe')");
        assertThrows(RenException.class, () -> service.create(42L, executable));
    }

    @Test
    void rejectsArbitraryStdioCommandsEvenWhenThePayloadSelfApprovesThem() {
        CapabilitySaveDTO request = new CapabilitySaveDTO();
        request.setType("MCP_SERVER");
        request.setName("任意命令");
        McpServerDTO mcp = new McpServerDTO();
        mcp.setTransport("STDIO");
        mcp.setConnectionConfig(Map.of("command", "python", "args", List.of("evil.py")));
        mcp.setApprovedCommandTemplate(Map.of(
                "command", "python", "argsPrefix", List.of("evil.py"), "extraArgPatterns", List.of()));
        mcp.setSecretRefs(Map.of());
        request.setMcp(mcp);

        assertThrows(RenException.class, () -> service.create(42L, request));
        verify(mcpServerDao, org.mockito.Mockito.never()).insert(any(McpServerEntity.class));
    }

    @Test
    void rejectsNetworkMcpConfigurationThatEmbedsAStdioCommand() {
        CapabilitySaveDTO request = new CapabilitySaveDTO();
        request.setType("MCP_SERVER");
        request.setName("混合传输");
        McpServerDTO mcp = new McpServerDTO();
        mcp.setTransport("SSE");
        mcp.setConnectionConfig(Map.of(
                "url", "https://mcp.example/sse",
                "command", "python",
                "args", List.of("evil.py")));
        mcp.setSecretRefs(Map.of());
        request.setMcp(mcp);

        assertThrows(RenException.class, () -> service.create(42L, request));
        verify(mcpServerDao, org.mockito.Mockito.never()).insert(any(McpServerEntity.class));
    }

    @Test
    void rejectsInlineSecretsInMcpConnectionConfiguration() {
        CapabilitySaveDTO request = new CapabilitySaveDTO();
        request.setType("MCP_SERVER");
        request.setName("带明文密钥的连接");
        McpServerDTO mcp = new McpServerDTO();
        mcp.setTransport("SSE");
        mcp.setConnectionConfig(Map.of(
                "url", "https://mcp.example/sse",
                "headers", Map.of("Authorization", "Bearer inline-secret")));
        mcp.setSecretRefs(Map.of());
        request.setMcp(mcp);

        assertThrows(RenException.class, () -> service.create(42L, request));
        verify(mcpServerDao, org.mockito.Mockito.never()).insert(any(McpServerEntity.class));
    }

    @Test
    void rejectsCredentialsEmbeddedInMcpUrls() {
        CapabilitySaveDTO request = new CapabilitySaveDTO();
        request.setType("MCP_SERVER");
        request.setName("带 URL 凭证的连接");
        McpServerDTO mcp = new McpServerDTO();
        mcp.setTransport("SSE");
        mcp.setConnectionConfig(Map.of("url", "https://user:password@mcp.example/sse"));
        mcp.setSecretRefs(Map.of());
        request.setMcp(mcp);

        assertThrows(RenException.class, () -> service.create(42L, request));
        verify(mcpServerDao, org.mockito.Mockito.never()).insert(any(McpServerEntity.class));
    }

    @Test
    void rejectsMissingToolReferencesAndMalformedRegex() {
        CapabilitySaveDTO missingTool = weatherSkill("prompt");
        assertThrows(RenException.class, () -> service.create(42L, missingTool));

        stubWeatherPlugin();
        CapabilitySaveDTO malformedRegex = weatherSkill("prompt");
        malformedRegex.getTriggers().get(0).setType("REGEX");
        malformedRegex.getTriggers().get(0).setValue("[");
        assertThrows(RenException.class, () -> service.create(42L, malformedRegex));
    }

    @Test
    void rejectsMcpToolsThatAreNotApprovedAndActive() {
        CapabilitySaveDTO skill = weatherSkill("prompt");
        SkillToolDTO tool = skill.getTools().getFirst();
        tool.setToolType("MCP");
        tool.setToolRefId("snapshot-search");
        tool.setToolName("mcp_search");

        McpToolSnapshotEntity snapshot = new McpToolSnapshotEntity();
        snapshot.setId("snapshot-search");
        snapshot.setToolName("mcp_search");
        snapshot.setApproved(0);
        snapshot.setStatus("DISCOVERED");
        when(mcpToolDao.selectById("snapshot-search")).thenReturn(snapshot);
        assertThrows(RenException.class, () -> service.create(42L, skill));

        snapshot.setApproved(1);
        snapshot.setStatus("DRIFTED");
        assertThrows(RenException.class, () -> service.create(42L, skill));

        snapshot.setStatus("MISSING");
        assertThrows(RenException.class, () -> service.create(42L, skill));
    }

    @Test
    void updatesOnlyTheDraftAggregate() {
        stubWeatherPlugin();
        CapabilityEntity capability = skillCapability("skill-1");
        SkillDefinitionEntity definition = definition("definition-1", "skill-1", "旧提示词");
        when(capabilityDao.selectForUpdate("skill-1")).thenReturn(capability);
        when(skillDefinitionDao.selectByCapabilityId("skill-1")).thenReturn(definition);

        var result = service.update(42L, "skill-1", weatherSkill("新提示词"));

        assertEquals("新提示词", result.getExecutionPrompt());
        assertEquals(2, capability.getDraftVersion());
        verify(capabilityDao).updateById(capability);
        verify(skillDefinitionDao).updateById(definition);
        verify(triggerDao).deleteBySkillId("skill-1");
        verify(toolMappingDao).deleteBySkillId("skill-1");
    }

    @Test
    void publishesImmutableVersionContent() {
        stubWeatherPlugin();
        CapabilityEntity capability = skillCapability("skill-1");
        SkillDefinitionEntity definition = definition("definition-1", "skill-1", "第一版提示词");
        when(capabilityDao.selectForUpdate("skill-1")).thenReturn(capability);
        when(skillDefinitionDao.selectByCapabilityId("skill-1")).thenReturn(definition);
        when(triggerDao.selectBySkillId("skill-1")).thenReturn(List.of(trigger("天气")));
        when(toolMappingDao.selectBySkillId("skill-1")).thenReturn(List.of(toolMapping()));
        when(versionDao.selectMaxVersion("skill-1")).thenReturn(null, 1);
        List<CapabilityVersionEntity> inserted = new ArrayList<>();
        when(versionDao.insert(any(CapabilityVersionEntity.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        service.publish(42L, "skill-1");
        definition.setExecutionPrompt("第二版提示词");
        service.publish(42L, "skill-1");

        assertEquals(2, inserted.size());
        assertEquals(1, inserted.get(0).getVersionNo());
        assertEquals(2, inserted.get(1).getVersionNo());
        assertTrue(inserted.get(0).getContentJson().contains("第一版提示词"));
        assertTrue(inserted.get(1).getContentJson().contains("第二版提示词"));
        assertNotEquals(inserted.get(0).getContentSha256(), inserted.get(1).getContentSha256());
        assertEquals(2, capability.getPublishedVersion());
        verify(deviceSkillMappingDao, org.mockito.Mockito.times(2))
                .bumpLatestDeviceConfigVersions(org.mockito.ArgumentMatchers.eq("skill-1"), any(Date.class));
    }

    @Test
    void refusesToPublishSkillAfterItsPluginIsDisabled() {
        CapabilityEntity skill = skillCapability("skill-1");
        when(capabilityDao.selectForUpdate("skill-1")).thenReturn(skill);
        when(skillDefinitionDao.selectByCapabilityId("skill-1"))
                .thenReturn(definition("definition-1", "skill-1", "查询天气"));
        when(triggerDao.selectBySkillId("skill-1")).thenReturn(List.of(trigger("天气")));
        when(toolMappingDao.selectBySkillId("skill-1")).thenReturn(List.of(toolMapping()));

        CapabilityEntity plugin = new CapabilityEntity();
        plugin.setId("plugin-weather");
        plugin.setType("PLUGIN");
        plugin.setStatus("DISABLED");
        when(capabilityDao.selectById("plugin-weather")).thenReturn(plugin);
        PluginDefinitionEntity definition = new PluginDefinitionEntity();
        definition.setCapabilityId("plugin-weather");
        definition.setExecutorName("get_weather");
        when(pluginDao.selectByCapabilityId("plugin-weather")).thenReturn(definition);

        assertThrows(RenException.class, () -> service.publish(42L, "skill-1"));
        verify(versionDao, org.mockito.Mockito.never()).insert(any(CapabilityVersionEntity.class));
    }

    @Test
    void disablesCapabilitiesAndRefusesToDeleteReferencedTools() {
        CapabilityEntity skill = skillCapability("skill-1");
        when(capabilityDao.selectForUpdate("skill-1")).thenReturn(skill);
        service.updateStatus(42L, "skill-1", "DISABLED");
        assertEquals("DISABLED", skill.getStatus());
        verify(deviceSkillMappingDao).bumpAllDeviceConfigVersions(
                org.mockito.ArgumentMatchers.eq("skill-1"), any(Date.class));

        CapabilityEntity plugin = new CapabilityEntity();
        plugin.setId("plugin-weather");
        plugin.setType("PLUGIN");
        plugin.setName("天气插件");
        plugin.setStatus("PUBLISHED");
        when(capabilityDao.selectForUpdate("plugin-weather")).thenReturn(plugin);
        when(toolMappingDao.countByToolRef("PLUGIN", "plugin-weather")).thenReturn(1L);

        assertThrows(RenException.class, () -> service.delete(42L, "plugin-weather"));
    }

    @Test
    void refusesToDeleteMcpServerWhenOneOfItsSnapshotToolsIsMapped() {
        CapabilityEntity mcp = new CapabilityEntity();
        mcp.setId("mcp-search");
        mcp.setType("MCP_SERVER");
        mcp.setName("搜索 MCP");
        mcp.setStatus("PUBLISHED");
        when(capabilityDao.selectForUpdate("mcp-search")).thenReturn(mcp);
        McpServerEntity server = new McpServerEntity();
        server.setId("mcp-server-row");
        server.setCapabilityId("mcp-search");
        when(mcpServerDao.selectByCapabilityId("mcp-search")).thenReturn(server);
        McpToolSnapshotEntity snapshot = new McpToolSnapshotEntity();
        snapshot.setId("mcp-tool-search");
        snapshot.setMcpServerId("mcp-server-row");
        when(mcpToolDao.selectByMcpServerId("mcp-server-row")).thenReturn(List.of(snapshot));
        when(toolMappingDao.countByToolRef("MCP", "mcp-tool-search")).thenReturn(1L);

        assertThrows(RenException.class, () -> service.delete(42L, "mcp-search"));
    }

    @Test
    void returnsMcpConnectionHealthWithoutExposingSecrets() {
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("mcp-search");
        capability.setType("MCP_SERVER");
        capability.setName("搜索 MCP");
        capability.setStatus("PUBLISHED");
        capability.setDeleted(0);
        when(capabilityDao.selectById("mcp-search")).thenReturn(capability);

        Date checkedAt = new Date(1_723_800_000_000L);
        McpServerEntity server = new McpServerEntity();
        server.setCapabilityId("mcp-search");
        server.setTransport("SSE");
        server.setConnectionConfigJson("{\"url\":\"https://mcp.example/sse\"}");
        server.setSecretRefsJson("{\"authorization\":\"secret-1\"}");
        server.setApprovedCommandTemplateJson("null");
        server.setHealthStatus("UNHEALTHY");
        server.setLastError("连接超时");
        server.setLastCheckedAt(checkedAt);
        when(mcpServerDao.selectByCapabilityId("mcp-search")).thenReturn(server);

        var result = service.get("mcp-search");

        assertEquals("UNHEALTHY", result.getMcp().getHealthStatus());
        assertEquals("连接超时", result.getMcp().getLastError());
        assertEquals(checkedAt, result.getMcp().getLastCheckedAt());
        assertEquals("secret-1", result.getMcp().getSecretRefs().get("authorization"));
    }

    private void stubWeatherPlugin() {
        CapabilityEntity plugin = new CapabilityEntity();
        plugin.setId("plugin-weather");
        plugin.setType("PLUGIN");
        plugin.setStatus("PUBLISHED");
        when(capabilityDao.selectById("plugin-weather")).thenReturn(plugin);
        PluginDefinitionEntity definition = new PluginDefinitionEntity();
        definition.setCapabilityId("plugin-weather");
        definition.setExecutorName("get_weather");
        when(pluginDao.selectByCapabilityId("plugin-weather")).thenReturn(definition);
    }

    private CapabilitySaveDTO weatherSkill(String prompt) {
        CapabilitySaveDTO dto = new CapabilitySaveDTO();
        dto.setType("SKILL");
        dto.setName("天气查询");
        dto.setDescription("查询指定地点天气");
        dto.setExecutionPrompt(prompt);
        dto.setSemanticThreshold(new BigDecimal("0.75"));
        dto.setResponseMode("LLM");
        dto.setTimeoutMs(15000);
        dto.setFailureMessage("天气查询失败");

        SkillTriggerDTO trigger = new SkillTriggerDTO();
        trigger.setType("KEYWORD");
        trigger.setValue("天气");
        trigger.setPriority(10);
        dto.setTriggers(List.of(trigger));

        SkillToolDTO tool = new SkillToolDTO();
        tool.setToolType("PLUGIN");
        tool.setToolRefId("plugin-weather");
        tool.setToolName("get_weather");
        tool.setDefaultParams(Map.of("location", "上海"));
        dto.setTools(List.of(tool));
        return dto;
    }

    private CapabilityEntity skillCapability(String id) {
        CapabilityEntity result = new CapabilityEntity();
        result.setId(id);
        result.setCapabilityCode(id);
        result.setType("SKILL");
        result.setName("天气查询");
        result.setDescription("查询指定地点天气");
        result.setStatus("DRAFT");
        result.setDraftVersion(1);
        result.setDeleted(0);
        return result;
    }

    private SkillDefinitionEntity definition(String id, String skillId, String prompt) {
        SkillDefinitionEntity result = new SkillDefinitionEntity();
        result.setId(id);
        result.setCapabilityId(skillId);
        result.setExecutionPrompt(prompt);
        result.setTriggerMode("MIXED");
        result.setRuleMode("ANY");
        result.setSemanticThreshold(new BigDecimal("0.75"));
        result.setResponseMode("LLM");
        result.setTimeoutMs(15000);
        result.setFailureMessage("天气查询失败");
        return result;
    }

    private SkillTriggerEntity trigger(String value) {
        SkillTriggerEntity result = new SkillTriggerEntity();
        result.setId(1L);
        result.setSkillId("skill-1");
        result.setTriggerType("KEYWORD");
        result.setPatternText(value);
        result.setPriority(10);
        result.setCaseSensitive(0);
        result.setEnabled(1);
        return result;
    }

    private SkillToolMappingEntity toolMapping() {
        SkillToolMappingEntity result = new SkillToolMappingEntity();
        result.setId(1L);
        result.setSkillId("skill-1");
        result.setToolType("PLUGIN");
        result.setToolRefId("plugin-weather");
        result.setToolName("get_weather");
        result.setDefaultParamsJson("{\"location\":\"上海\"}");
        return result;
    }
}
