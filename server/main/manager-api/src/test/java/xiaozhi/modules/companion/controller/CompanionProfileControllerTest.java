package xiaozhi.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import xiaozhi.modules.companion.dto.CompanionProfileCreateDTO;
import xiaozhi.modules.companion.dto.CompanionProfileSaveDTO;
import xiaozhi.modules.companion.service.CompanionProfileService;
import xiaozhi.modules.security.user.SecurityUser;

class CompanionProfileControllerTest {
    @Test
    void controllerUsesCurrentUserForEveryOperation() {
        CompanionProfileService service = mock(CompanionProfileService.class);
        CompanionProfileController controller = new CompanionProfileController(service);
        CompanionProfileCreateDTO create = new CompanionProfileCreateDTO();
        create.setTemplateId("template-id");
        create.setName("Nova");
        CompanionProfileSaveDTO update = new CompanionProfileSaveDTO();

        try (MockedStatic<SecurityUser> securityUser = mockStatic(SecurityUser.class)) {
            securityUser.when(SecurityUser::getUserId).thenReturn(7L);
            controller.list();
            controller.create(create);
            controller.get("agent-id");
            controller.update("agent-id", update);
            controller.restorePrompt("agent-id");
            controller.delete("agent-id");
        }

        verify(service).list(7L);
        verify(service).createFromTemplate(7L, "template-id", "Nova");
        verify(service).get(7L, "agent-id");
        verify(service).update(7L, "agent-id", update);
        verify(service).restorePrompt(7L, "agent-id");
        verify(service).delete(7L, "agent-id");
    }

    @Test
    void routesAndPermissionsMatchCompanionProfileApi() throws Exception {
        RequestMapping root = CompanionProfileController.class.getAnnotation(RequestMapping.class);
        assertEquals(List.of("/companion/profiles"), List.of(root.value()));

        assertRoute("list", GetMapping.class, "");
        assertRoute("create", PostMapping.class, "", CompanionProfileCreateDTO.class);
        assertRoute("get", GetMapping.class, "/{id}", String.class);
        assertRoute("update", PutMapping.class, "/{id}", String.class, CompanionProfileSaveDTO.class);
        assertRoute("restorePrompt", PostMapping.class, "/{id}/restore-prompt", String.class);
        assertRoute("delete", DeleteMapping.class, "/{id}", String.class);
    }

    private void assertRoute(String name, Class<?> mappingType, String expectedPath, Class<?>... parameters)
            throws Exception {
        Method method = CompanionProfileController.class.getMethod(name, parameters);
        Object mapping = method.getAnnotation(mappingType.asSubclass(java.lang.annotation.Annotation.class));
        assertNotNull(mapping);
        String[] paths;
        if (mapping instanceof GetMapping annotation) {
            paths = annotation.value();
        } else if (mapping instanceof PostMapping annotation) {
            paths = annotation.value();
        } else if (mapping instanceof PutMapping annotation) {
            paths = annotation.value();
        } else {
            paths = ((DeleteMapping) mapping).value();
        }
        assertEquals(expectedPath, paths.length == 0 ? "" : paths[0]);
        RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
        assertNotNull(permission);
        assertEquals(List.of("sys:role:normal"), List.of(permission.value()));
    }
}
