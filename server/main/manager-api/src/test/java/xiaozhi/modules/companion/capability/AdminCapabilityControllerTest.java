package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.common.page.PageData;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.companion.capability.controller.AdminCapabilityController;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.service.CapabilityMigrationAuditService;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.service.McpCapabilityService;
import xiaozhi.modules.companion.capability.service.McpLocalConfigImportService;
import xiaozhi.modules.companion.capability.vo.CapabilityRoutePreviewVO;
import xiaozhi.modules.companion.capability.vo.CapabilityMigrationAuditVO;
import xiaozhi.modules.companion.capability.vo.CapabilityVO;
import xiaozhi.modules.companion.capability.vo.McpLocalConfigImportVO;

class AdminCapabilityControllerTest {

    @AfterEach
    void clearSubject() {
        ThreadContext.unbindSubject();
    }

    @Test
    void everyEndpointRequiresSuperAdminPermission() {
        for (Method method : AdminCapabilityController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
            assertNotNull(permission, method.getName());
            assertEquals(List.of("sys:role:superAdmin"), List.of(permission.value()), method.getName());
        }
    }

    @Test
    void exposesCapabilityMutationsWithoutReturningSecrets() throws Exception {
        CapabilityService capabilities = mock(CapabilityService.class);
        CapabilitySecretService secrets = mock(CapabilitySecretService.class);
        CapabilityRoutePreviewService preview = mock(CapabilityRoutePreviewService.class);
        CapabilityVO skill = new CapabilityVO();
        skill.setId("skill-weather");
        skill.setType("SKILL");
        skill.setName("天气查询");
        skill.setStatus("PUBLISHED");
        when(capabilities.page(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(new PageData<>(List.of(skill), 1));
        when(capabilities.get("skill-weather")).thenReturn(skill);
        when(capabilities.create(any(), any())).thenReturn(skill);
        when(capabilities.update(any(), any(), any())).thenReturn(skill);
        when(capabilities.publish(any(), any())).thenReturn(skill);
        when(secrets.save(7L, "skill-weather", "api_key", "new-secret"))
                .thenReturn(true);
        when(secrets.status("skill-weather")).thenReturn(Map.of("api_key", true));
        MockMvc mvc = mvc(capabilities, secrets, preview);

        mvc.perform(get("/admin/companion/capabilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/admin/companion/capabilities/skill-weather"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("skill-weather"));
        String skillBody = "{\"type\":\"SKILL\",\"name\":\"天气查询\",\"executionPrompt\":\"查天气\","
                + "\"semanticThreshold\":0.7,\"responseMode\":\"LLM\",\"timeoutMs\":10000,"
                + "\"triggers\":[{\"type\":\"KEYWORD\",\"value\":\"天气\"}],"
                + "\"tools\":[{\"toolType\":\"PLUGIN\",\"toolRefId\":\"plugin-weather\",\"toolName\":\"get_weather\"}]}";
        mvc.perform(post("/admin/companion/capabilities").contentType(MediaType.APPLICATION_JSON).content(skillBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("skill-weather"));
        mvc.perform(put("/admin/companion/capabilities/skill-weather").contentType(MediaType.APPLICATION_JSON)
                        .content(skillBody))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/companion/capabilities/skill-weather/publish"))
                .andExpect(status().isOk());
        mvc.perform(put("/admin/companion/capabilities/skill-weather/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/companion/capabilities/skill-weather/secrets"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.api_key").value(true));
        mvc.perform(put("/admin/companion/capabilities/skill-weather/secrets/api_key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"new-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("new-secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ciphertext"))));
        mvc.perform(delete("/admin/companion/capabilities/skill-weather"))
                .andExpect(status().isOk());

        verify(capabilities).updateStatus(7L, "skill-weather", "DISABLED");
        verify(capabilities).delete(7L, "skill-weather");
    }

    @Test
    void routePreviewReturnsMatchesAndAllowedToolsWithoutExecution() throws Exception {
        CapabilityService capabilities = mock(CapabilityService.class);
        CapabilitySecretService secrets = mock(CapabilitySecretService.class);
        CapabilityRoutePreviewService preview = mock(CapabilityRoutePreviewService.class);
        CapabilityRoutePreviewVO result = new CapabilityRoutePreviewVO();
        result.setDeterministicMatches(List.of("skill-weather"));
        result.setSemanticRequired(false);
        result.setEligibleSkillIds(List.of("skill-weather"));
        result.setSelectedSkillId("skill-weather");
        result.setAllowedTools(List.of("get_weather"));
        when(preview.preview("device-1", "上海天气怎么样")).thenReturn(result);

        mvc(capabilities, secrets, preview).perform(post("/admin/companion/capabilities/route-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"device-1\",\"utterance\":\"上海天气怎么样\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedSkillId").value("skill-weather"))
                .andExpect(jsonPath("$.data.semanticRequired").value(false))
                .andExpect(jsonPath("$.data.allowedTools[0]").value("get_weather"));
    }

    @Test
    void superAdminCanManageBindingsForOneDevice() {
        DeviceCapabilityService deviceCapabilities = mock(DeviceCapabilityService.class);
        AdminCapabilityController controller = new AdminCapabilityController(
                mock(CapabilityService.class), mock(CapabilitySecretService.class),
                mock(CapabilityRoutePreviewService.class), deviceCapabilities);
        List<DeviceSkillBindingDTO> request = List.of();

        try (MockedStatic<xiaozhi.modules.security.user.SecurityUser> security =
                mockStatic(xiaozhi.modules.security.user.SecurityUser.class)) {
            security.when(xiaozhi.modules.security.user.SecurityUser::getUserId).thenReturn(99L);
            controller.saveDeviceSkills("device-1", request);
            controller.deviceSkillCatalog("device-1");
        }

        verify(deviceCapabilities).save(99L, "device-1", request, true);
        verify(deviceCapabilities).catalog(99L, "device-1", true);
    }

    @Test
    void superAdminCanReadMigrationAuditAndImportLocalMcpJson() {
        CapabilityMigrationAuditService migrationAudit = mock(CapabilityMigrationAuditService.class);
        McpLocalConfigImportService mcpImport = mock(McpLocalConfigImportService.class);
        CapabilityMigrationAuditVO report = new CapabilityMigrationAuditVO();
        report.setUnmappedCount(1);
        when(migrationAudit.report()).thenReturn(report);
        McpLocalConfigImportVO imported = new McpLocalConfigImportVO();
        imported.setImported(List.of("search"));
        when(mcpImport.importDocument(eq(99L), any())).thenReturn(imported);
        AdminCapabilityController controller = new AdminCapabilityController(
                mock(CapabilityService.class), mock(CapabilitySecretService.class),
                mock(CapabilityRoutePreviewService.class), mock(DeviceCapabilityService.class),
                mock(McpCapabilityService.class), migrationAudit, mcpImport);

        try (MockedStatic<xiaozhi.modules.security.user.SecurityUser> security =
                mockStatic(xiaozhi.modules.security.user.SecurityUser.class)) {
            security.when(xiaozhi.modules.security.user.SecurityUser::getUserId).thenReturn(99L);
            assertEquals(1, controller.migrationAudit().getData().getUnmappedCount());
            assertEquals(List.of("search"), controller.importLocalMcp(Map.of("mcpServers", Map.of())).getData().getImported());
        }

        verify(migrationAudit).report();
        verify(mcpImport).importDocument(eq(99L), any());
    }

    private MockMvc mvc(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService preview) {
        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(7L);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
        return MockMvcBuilders.standaloneSetup(new AdminCapabilityController(capabilities, secrets, preview)).build();
    }
}
