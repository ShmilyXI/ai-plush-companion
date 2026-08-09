package xiaozhi.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import xiaozhi.common.exception.RenExceptionHandler;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.companion.dto.AdminSystemSettingsSaveDTO;
import xiaozhi.modules.companion.service.AdminSystemSettingsService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.companion.service.impl.CompanionAdminAuditAspect;
import xiaozhi.modules.companion.vo.AdminSystemSettingsVO;

class AdminSystemSettingsControllerTest {

    @Test
    void everyEndpointRequiresSuperAdmin() {
        for (Method method : AdminSystemSettingsController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
            assertNotNull(permission, method.getName());
            assertArrayEquals(new String[] { "sys:role:superAdmin" }, permission.value());
        }
    }

    @Test
    void getReturnsSafeSettingsDocument() throws Exception {
        AdminSystemSettingsService service = mock(AdminSystemSettingsService.class);
        when(service.get()).thenReturn(AdminSystemSettingsVO.builder()
                .publicWebsocketUrl("wss://pet.example/ws")
                .modelOptions(java.util.Map.of())
                .voices(List.of())
                .health(java.util.Map.of())
                .restartServices(List.of())
                .build());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminSystemSettingsController(service)).build();

        mvc.perform(get("/admin/companion/system-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicWebsocketUrl").value("wss://pet.example/ws"));
    }

    @Test
    void invalidSaveBodyIsRejectedBeforeTheService() throws Exception {
        AdminSystemSettingsService service = mock(AdminSystemSettingsService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminSystemSettingsController(service))
                .setValidator(validator)
                .setControllerAdvice(new RenExceptionHandler())
                .build();

        mvc.perform(put("/admin/companion/system-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).save(any(), any());
    }

    @Test
    void savePassesTheAuthenticatedOperator() {
        AdminSystemSettingsService service = mock(AdminSystemSettingsService.class);
        AdminSystemSettingsController controller = new AdminSystemSettingsController(service);
        AdminSystemSettingsSaveDTO request = new AdminSystemSettingsSaveDTO();
        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(7L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            controller.save(request);
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(service).save(7L, request);
    }

    @Test
    void dedicatedSettingsAuditIsNotDuplicatedByTheLegacyAspect() throws Throwable {
        CompanionAuditService audit = mock(CompanionAuditService.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        CompanionAdminAuditAspect aspect = new CompanionAdminAuditAspect(audit, transactions);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = AdminSystemSettingsController.class.getMethod("save", AdminSystemSettingsSaveDTO.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.auditLegacyAdminMutation(joinPoint, method.getAnnotation(RequiresPermissions.class));

        verify(joinPoint).proceed();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
        verify(transactions, never()).getTransaction(any());
    }
}
