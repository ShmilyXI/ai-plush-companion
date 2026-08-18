package xiaozhi.modules.companion.capability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.companion.capability.controller.AdminCapabilityController;
import xiaozhi.modules.companion.capability.service.CapabilityMigrationAuditService;
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.service.CapabilityRuntimeClient;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.service.McpCapabilityService;
import xiaozhi.modules.companion.capability.service.McpLocalConfigImportService;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.companion.capability.vo.SkillPackageImportVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;

class SkillPackageControllerTest {

    @AfterEach
    void clearSubject() {
        ThreadContext.unbindSubject();
    }

    @Test
    void importsAndDownloadsSkillPackages() throws Exception {
        SkillPackageService packages = mock(SkillPackageService.class);
        SkillPackageValidationVO validation = new SkillPackageValidationVO();
        validation.setStatus("VALID");
        SkillPackageImportVO imported = new SkillPackageImportVO();
        imported.setCapabilityId("skill-weather");
        imported.setManifest(Map.of("id", "skill-weather", "version", 1));
        imported.setValidation(validation);
        when(packages.inspect(any())).thenReturn(imported);
        when(packages.download("skill-weather", 1)).thenReturn("archive".getBytes());
        MockMvc mvc = mvc(packages);
        MockMultipartFile file = new MockMultipartFile(
                "file", "weather.skill.zip", "application/zip", "archive".getBytes());

        mvc.perform(multipart("/admin/companion/capabilities/skill-packages/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation.status").value("VALID"));
        mvc.perform(get("/admin/companion/capabilities/skill-weather/packages/1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("skill-weather-1.skill.zip")));
    }

    private MockMvc mvc(SkillPackageService packages) {
        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(7L);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
        AdminCapabilityController controller = new AdminCapabilityController(
                mock(CapabilityService.class), mock(CapabilitySecretService.class),
                mock(CapabilityRoutePreviewService.class), mock(DeviceCapabilityService.class),
                mock(McpCapabilityService.class), mock(CapabilityMigrationAuditService.class),
                mock(McpLocalConfigImportService.class), mock(CapabilityRuntimeClient.class), packages);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
}
