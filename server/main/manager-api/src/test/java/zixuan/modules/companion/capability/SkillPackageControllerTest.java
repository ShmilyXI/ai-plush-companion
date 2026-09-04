package zixuan.modules.companion.capability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import zixuan.common.user.UserDetail;
import zixuan.modules.companion.capability.controller.AdminCapabilityController;
import zixuan.modules.companion.capability.service.CapabilityMigrationAuditService;
import zixuan.modules.companion.capability.service.CapabilityRoutePreviewService;
import zixuan.modules.companion.capability.service.CapabilityRuntimeClient;
import zixuan.modules.companion.capability.service.CapabilitySecretService;
import zixuan.modules.companion.capability.service.CapabilityService;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.service.McpCapabilityService;
import zixuan.modules.companion.capability.service.McpLocalConfigImportService;
import zixuan.modules.companion.capability.service.SkillPackageService;
import zixuan.modules.companion.capability.vo.SkillPackageImportVO;
import zixuan.modules.companion.capability.vo.SkillPackageVO;
import zixuan.modules.companion.capability.vo.SkillPackageValidationVO;

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
        SkillPackageVO version = new SkillPackageVO();
        version.setCapabilityId("skill-weather");
        version.setVersion(1);
        version.setPackageSha256("a".repeat(64));
        version.setPackageSize(7L);
        version.setSource("UPLOAD");
        version.setValidationStatus("VALID");
        version.setPublished(true);
        when(packages.list("skill-weather")).thenReturn(java.util.List.of(version));
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
        mvc.perform(get("/admin/companion/capabilities/skill-weather/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(1));
        mvc.perform(delete("/admin/companion/capabilities/skill-weather/packages/1"))
                .andExpect(status().isOk());
        verify(packages).deleteVersion(7L, "skill-weather", 1);
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
