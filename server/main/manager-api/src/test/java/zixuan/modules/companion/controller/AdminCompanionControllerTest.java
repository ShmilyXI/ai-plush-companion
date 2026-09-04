package zixuan.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.MediaType;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import zixuan.modules.companion.dao.CompanionPlanDao;
import zixuan.modules.companion.dao.CompanionSubscriptionDao;
import zixuan.modules.companion.dao.CompanionAuditDao;
import zixuan.modules.companion.dto.CompanionGrantDTO;
import zixuan.modules.companion.dto.AdminCompanionDeviceUpdateDTO;
import zixuan.modules.companion.entity.CompanionAuditEntity;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.companion.service.AdminFirmwareService;
import zixuan.modules.companion.service.AdminResourceService;
import zixuan.modules.companion.service.AdminCompanionDeviceService;
import zixuan.modules.companion.model.service.CompanionModelMigrationAuditService;
import zixuan.modules.companion.model.vo.CompanionModelMigrationAuditReportVO;
import zixuan.modules.companion.service.impl.CompanionAuditServiceImpl;
import zixuan.modules.companion.service.impl.CompanionAdminAuditAspect;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.device.service.CompanionMemoryService;
import zixuan.modules.device.service.CompanionMemoryService.MemoryItem;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.device.service.OtaService;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.entity.OtaEntity;
import zixuan.modules.device.controller.OTAMagController;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.sys.service.SysUserService;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.voiceclone.service.VoiceCloneService;
import zixuan.modules.sys.controller.AdminController;
import zixuan.modules.model.service.ModelProviderService;
import zixuan.common.user.UserDetail;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.exception.RenExceptionHandler;
import zixuan.common.utils.Result;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import jakarta.validation.Valid;
import jakarta.servlet.ServletException;

class AdminCompanionControllerTest {

    @Test
    void everyAdminEndpointRequiresSuperAdminPermission() {
        for (Method method : AdminCompanionController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
            assertNotNull(permission, method.getName());
            assertEquals(List.of("sys:role:superAdmin"), List.of(permission.value()), method.getName());
            for (var parameter : method.getParameters()) {
                if (parameter.getType().getSimpleName().endsWith("DTO")) {
                    assertNotNull(parameter.getAnnotation(Valid.class), method.getName());
                }
            }
        }
    }

    @Test
    void invalidPlanAndGrantBodiesReturnBadRequest() throws Exception {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class)))
                .setValidator(validator)
                .setControllerAdvice(new RenExceptionHandler())
                .build();

        mvc.perform(post("/admin/companion/plans").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/companion/subscriptions/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planId\":null,\"expiresAt\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_VALUE_NULL));
    }

    @Test
    void shiroIdentityBlocksNormalUserAcrossAdminResourceClasses() throws Exception {
        ProxyFactory proxyFactory = new ProxyFactory(controller(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class)));
        proxyFactory.addAdvisor(new AuthorizationAttributeSourceAdvisor());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(proxyFactory.getProxy())
                .build();
        Subject normal = mock(Subject.class);
        doThrow(new UnauthorizedException()).when(normal).checkPermission("sys:role:superAdmin");
        ThreadContext.bind(normal);
        try {
            for (String path : List.of(
                    "/admin/companion/plans",
                    "/admin/companion/users",
                    "/admin/companion/resources/models?modelType=TTS",
                    "/admin/companion/resources/timbres?ttsModelId=t1",
                    "/admin/companion/model-migration-audit",
                    "/admin/companion/audit")) {
                ServletException error = assertThrows(ServletException.class, () -> mvc.perform(get(path)));
                assertTrue(error.getCause() instanceof UnauthorizedException, path);
            }
            ServletException firmwareError = assertThrows(ServletException.class,
                    () -> mvc.perform(delete("/admin/companion/firmware/f1")));
            assertTrue(firmwareError.getCause() instanceof UnauthorizedException);
        } finally {
            ThreadContext.unbindSubject();
        }
    }

    @Test
    void modelMigrationAuditReturnsOnlyTheSafeReportShape() throws Exception {
        CompanionModelMigrationAuditService migrationAuditService = mock(CompanionModelMigrationAuditService.class);
        CompanionModelMigrationAuditReportVO report = new CompanionModelMigrationAuditReportVO(
                2, List.of("agent-ready"), List.of(), List.of("agent-missing"), List.of(),
                List.of("agent-missing"));
        when(migrationAuditService.audit()).thenReturn(report);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), migrationAuditService))
                .build();

        mvc.perform(get("/admin/companion/model-migration-audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalProfiles").value(2))
                .andExpect(jsonPath("$.data.readyAgentIds[0]").value("agent-ready"))
                .andExpect(jsonPath("$.data.needsSelectionAgentIds[0]").value("agent-missing"))
                .andExpect(jsonPath("$.data.systemPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.configJson").doesNotExist())
                .andExpect(jsonPath("$.data.secret").doesNotExist());
        verify(migrationAuditService).audit();
    }

    @Test
    void grantWritesOnlyAWhitelistedAuditSummaryAfterGrant() {
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        AdminCompanionController controller = controller(subscriptionService, auditService);
        CompanionGrantDTO dto = new CompanionGrantDTO();
        dto.setPlanId("pro");
        dto.setExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));

        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(1L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            controller.grant(9L, dto);
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(subscriptionService).grant(1L, 9L, "pro", dto.getExpiresAt());
        verify(auditService).record(eq(1L), eq(9L), eq("subscription.grant"),
                eq("subscription"), eq("9"), eq(Map.of(
                        "planId", "pro",
                        "expiresAt", "2027-01-01T00:00:00Z")));
        String summary = Map.of("planId", "pro", "expiresAt", dto.getExpiresAt().toString()).toString();
        assertFalse(summary.toLowerCase().contains("password"));
        assertFalse(summary.toLowerCase().contains("token"));
        assertFalse(summary.toLowerCase().contains("memory"));
    }

    @Test
    void pauseAndCancelSubscriptionRecordAuditAfterTheStateTransition() {
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        AdminCompanionController controller = controller(subscriptionService, auditService);
        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(1L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            controller.pauseSubscription(9L);
            controller.cancelSubscription(9L);
        } finally {
            ThreadContext.unbindSubject();
        }

        InOrder order = inOrder(subscriptionService, auditService);
        order.verify(subscriptionService).pause(1L, 9L);
        order.verify(auditService).record(1L, 9L, "subscription.pause", "subscription", "9",
                Map.of("status", "paused"));
        order.verify(subscriptionService).cancel(1L, 9L);
        order.verify(auditService).record(1L, 9L, "subscription.cancel", "subscription", "9",
                Map.of("status", "cancelled"));
    }

    @Test
    void auditServiceMasksSecretsAndNeverStoresFullMemoryContent() {
        CompanionAuditDao dao = mock(CompanionAuditDao.class);
        when(dao.insert(any(CompanionAuditEntity.class))).thenReturn(1);
        CompanionAuditService service = new CompanionAuditServiceImpl(dao);
        String memory = "private-memory-".repeat(80);

        service.record(1L, 9L, "profile.update", "profile", "p1", Map.of(
                "displayName", "Alice",
                "serverSecret", "secret-value",
                "memoryContent", memory,
                "token", "token-value"));

        ArgumentCaptor<CompanionAuditEntity> captor = ArgumentCaptor.forClass(CompanionAuditEntity.class);
        verify(dao).insert(captor.capture());
        String stored = captor.getValue().getSummary();
        assertTrue(stored.contains("displayName=Alice"));
        assertFalse(stored.contains("secret-value"));
        assertFalse(stored.contains("token-value"));
        assertFalse(stored.contains(memory));
        assertTrue(stored.length() <= 1000);
    }

    @Test
    void safeModelResponseHasNoRawConfigurationOrSecretFields() {
        Set<String> fields = java.util.Arrays.stream(AdminCompanionController.SafeModelVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());
        assertFalse(fields.contains("configJson"));
        assertFalse(fields.contains("config"));
        assertFalse(fields.contains("apiKey"));
        assertFalse(fields.contains("secret"));
        assertTrue(fields.contains("providerCode"));
        assertTrue(fields.contains("profileUsageCount"));
        assertTrue(fields.contains("deviceUsageCount"));
    }

    @Test
    void modelUpdateDtoExposesOnlyMutableFields() {
        Set<String> fields = java.util.Arrays.stream(AdminCompanionController.AdminModelUpdateDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("modelName", "isEnabled", "remark", "sort", "config"), fields);
    }

    @Test
    void userPageRejectsInvalidPageAndLimitValues() {
        SysUserService userService = mock(SysUserService.class);
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), userService, mock(ModelConfigService.class),
                mock(AdminFirmwareService.class), mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));

        assertThrows(zixuan.common.exception.RenException.class, () -> controller.users("0", "20", null));
        assertThrows(zixuan.common.exception.RenException.class, () -> controller.users("1", "101", null));
        assertThrows(zixuan.common.exception.RenException.class, () -> controller.users("abc", "20", null));

        verify(userService, org.mockito.Mockito.never()).page(any());
    }

    @Test
    void userPageAcceptsPageNumbersAboveOneHundred() {
        SysUserService userService = mock(SysUserService.class);
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), userService, mock(ModelConfigService.class),
                mock(AdminFirmwareService.class), mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));

        controller.users("101", "100", null);

        ArgumentCaptor<zixuan.modules.sys.dto.AdminPageUserDTO> captor =
                ArgumentCaptor.forClass(zixuan.modules.sys.dto.AdminPageUserDTO.class);
        verify(userService).page(captor.capture());
        assertEquals("101", captor.getValue().getPage());
        assertEquals("100", captor.getValue().getLimit());
    }

    @Test
    void legacySuperAdminMutationIsAuditedInsideTheWrapperTransaction() throws Throwable {
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        CompanionAdminAuditAspect aspect = new CompanionAdminAuditAspect(auditService, transactionManager);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = AdminController.class.getMethod("update", Long.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] { 9L });
        when(joinPoint.proceed()).thenReturn(new Object());
        RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(1L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            aspect.auditLegacyAdminMutation(joinPoint, permission);
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(auditService).record(1L, 9L, "legacy.admin.update", "admin", "9", Map.of("method", "update"));
        verify(transactionManager).commit(any());
    }

    @Test
    void rejectedLegacyFirmwareUploadRollsBackWithoutAnAudit() throws Throwable {
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        CompanionAdminAuditAspect aspect = new CompanionAdminAuditAspect(auditService, transactionManager);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = OTAMagController.class.getMethod("uploadFirmware", org.springframework.web.multipart.MultipartFile.class);
        MockMultipartFile file = new MockMultipartFile("file", "firmware.bin", "application/octet-stream",
                new byte[] { 1 });
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] { file });
        when(joinPoint.proceed()).thenReturn(new Result<String>().error("请使用带固件元数据的管理上传接口"));
        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(1L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            aspect.auditLegacyAdminMutation(joinPoint, method.getAnnotation(RequiresPermissions.class));
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(auditService, org.mockito.Mockito.never()).record(any(), any(), any(), any(), any(), any());
        verify(transactionStatus).setRollbackOnly();
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void legacyFirmwareDelegationIsNotAuditedTwiceByTheControllerAspect() throws Throwable {
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        CompanionAdminAuditAspect aspect = new CompanionAdminAuditAspect(auditService, transactionManager);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = OTAMagController.class.getMethod("update", String.class,
                zixuan.modules.device.entity.OtaEntity.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] { "f1", new zixuan.modules.device.entity.OtaEntity() });
        when(joinPoint.proceed()).thenReturn(new Result<Void>());

        aspect.auditLegacyAdminMutation(joinPoint, method.getAnnotation(RequiresPermissions.class));

        verify(joinPoint).proceed();
        verify(auditService, org.mockito.Mockito.never()).record(any(), any(), any(), any(), any(), any());
        verify(transactionManager, org.mockito.Mockito.never()).getTransaction(any());
    }

    @Test
    void legacyFailureResultIsNotRecordedAsSuccessfulAudit() throws Throwable {
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        java.util.concurrent.atomic.AtomicBoolean rollbackOnly = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> { rollbackOnly.set(true); return null; })
                .when(transactionStatus).setRollbackOnly();
        when(transactionStatus.isRollbackOnly()).thenAnswer(invocation -> rollbackOnly.get());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        CompanionAdminAuditAspect aspect = new CompanionAdminAuditAspect(auditService, transactionManager);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = AdminController.class.getMethod("update", Long.class);
        when(joinPoint.getSignature()).thenReturn(signature); when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] { 9L });
        when(joinPoint.proceed()).thenReturn(new Result<Void>().error("修改失败"));

        aspect.auditLegacyAdminMutation(joinPoint, method.getAnnotation(RequiresPermissions.class));

        verify(auditService, org.mockito.Mockito.never()).record(any(), any(), any(), any(), any(), any());
        verify(transactionStatus).setRollbackOnly();
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void failedFirmwareUploadDoesNotInsertMetadataRow() {
        AdminFirmwareService firmwareService = mock(AdminFirmwareService.class);
        doThrow(new zixuan.common.exception.RenException("上传失败")).when(firmwareService)
                .upload(any(), any(), any(), any(), any(), any());
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), mock(ModelConfigService.class),
                firmwareService, mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));

        org.junit.jupiter.api.Assertions.assertThrows(zixuan.common.exception.RenException.class,
                () -> controller.uploadFirmware(new MockMultipartFile("file", "bad.bin", "application/octet-stream", new byte[] { 1 }),
                        "固件", "esp32", "1.0.0", null));

        verify(firmwareService).upload(any(), any(), eq("固件"), eq("esp32"), eq("1.0.0"), eq(null));
    }

    @Test
    void multipartFirmwareUploadPersistsTheReturnedFilePathAndSize() throws Exception {
        AdminFirmwareService firmwareService = mock(AdminFirmwareService.class);
        when(firmwareService.upload(any(), any(), any(), any(), any(), any())).thenReturn("f1");
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), mock(ModelConfigService.class),
                firmwareService, mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockMultipartFile file = new MockMultipartFile("file", "firmware.bin", "application/octet-stream",
                new byte[] { 1, 2, 3 });
        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(1L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            mvc.perform(multipart("/admin/companion/firmware/upload").file(file)
                    .param("firmwareName", "稳定版").param("type", "esp32").param("version", "1.0.0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(firmwareService).upload(eq(1L), eq(file), eq("稳定版"), eq("esp32"), eq("1.0.0"), eq(null));
    }

    @Test
    void settingDefaultModelUpdatesTheDefaultTemplateReference() {
        ModelConfigService modelService = mock(ModelConfigService.class);
        AdminResourceService resourceService = mock(AdminResourceService.class);
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId("m1");
        entity.setModelType("TTS");
        when(modelService.selectById("m1")).thenReturn(entity);
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), modelService,
                mock(AdminFirmwareService.class), resourceService, mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));
        Subject subject = mock(Subject.class);
        UserDetail operator = new UserDetail();
        operator.setId(1L);
        when(subject.getPrincipal()).thenReturn(operator);
        ThreadContext.bind(subject);
        try {
            controller.defaultModel("m1");
        } finally {
            ThreadContext.unbindSubject();
        }

        verify(resourceService).setDefaultModel(1L, "m1");
    }

    @Test
    void visionModelsAreAvailableToAdministratorResourceRoutes() {
        ModelProviderService providerService = mock(ModelProviderService.class);
        when(providerService.getListByModelType("VLLM")).thenReturn(List.of());
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), mock(ModelConfigService.class),
                mock(AdminFirmwareService.class), mock(AdminResourceService.class), mock(TimbreService.class),
                providerService, mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));

        controller.providers("VLLM");

        verify(providerService).getListByModelType("VLLM");
    }

    @Test
    void administratorMemoryRoutesUseTheDevicesActualOwner() {
        CompanionMemoryService memoryService = mock(CompanionMemoryService.class);
        DeviceService deviceService = mock(DeviceService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("d1");
        device.setUserId(9L);
        when(deviceService.selectById("d1")).thenReturn(device);
        when(memoryService.list(1L, 9L, "d1"))
                .thenReturn(List.of(new MemoryItem("m1", "内容", "2026-07-31", null, null, null, null)));
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), mock(ModelConfigService.class),
                mock(AdminFirmwareService.class), mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), memoryService, deviceService,
                mock(AdminCompanionDeviceService.class), mock(CompanionModelMigrationAuditService.class));

        try (org.mockito.MockedStatic<zixuan.modules.security.user.SecurityUser> securityUser =
                org.mockito.Mockito.mockStatic(zixuan.modules.security.user.SecurityUser.class)) {
            securityUser.when(zixuan.modules.security.user.SecurityUser::getUserId).thenReturn(1L);
            assertEquals("m1", controller.adminMemories("d1").getData().get(0).id());
            controller.updateAdminMemory("d1", "m1", new CompanionMemoryController.MemoryUpdateRequest("新内容"));
            controller.deleteAdminMemory("d1", "m1");
            controller.clearAdminMemories("d1");
        }

        verify(memoryService).update(1L, 9L, "d1", "m1", "新内容");
        verify(memoryService).delete(1L, 9L, "d1", "m1");
        verify(memoryService).clear(1L, 9L, "d1");

        when(deviceService.selectById("missing")).thenReturn(null);
        assertThrows(RenException.class, () -> controller.adminMemories("missing"));
    }

    @Test
    void administratorDeviceRoutesExposeOnlyRenameAndUnbind() {
        AdminCompanionDeviceService deviceAdminService = mock(AdminCompanionDeviceService.class);
        AdminCompanionController controller = new AdminCompanionController(
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), mock(ModelConfigService.class),
                mock(AdminFirmwareService.class), mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                deviceAdminService, mock(CompanionModelMigrationAuditService.class));
        AdminCompanionDeviceUpdateDTO request = new AdminCompanionDeviceUpdateDTO();
        request.setAlias("床头伙伴");

        try (org.mockito.MockedStatic<zixuan.modules.security.user.SecurityUser> securityUser =
                org.mockito.Mockito.mockStatic(zixuan.modules.security.user.SecurityUser.class)) {
            securityUser.when(zixuan.modules.security.user.SecurityUser::getUserId).thenReturn(1L);
            controller.updateAdminDevice("d1", request);
            controller.unbindAdminDevice("d1");
        }

        verify(deviceAdminService).rename(1L, "d1", "床头伙伴");
        verify(deviceAdminService).unbind(1L, "d1");
        Set<String> fields = java.util.Arrays.stream(AdminCompanionDeviceUpdateDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("alias"), fields);
    }

    private AdminCompanionController controller(CompanionSubscriptionService subscriptionService,
            CompanionAuditService auditService) {
        return controller(subscriptionService, auditService, mock(CompanionModelMigrationAuditService.class));
    }

    private AdminCompanionController controller(CompanionSubscriptionService subscriptionService,
            CompanionAuditService auditService, CompanionModelMigrationAuditService migrationAuditService) {
        return new AdminCompanionController(subscriptionService, auditService, mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(SysUserService.class), mock(ModelConfigService.class),
                mock(AdminFirmwareService.class), mock(AdminResourceService.class), mock(TimbreService.class),
                mock(ModelProviderService.class), mock(CompanionMemoryService.class), mock(DeviceService.class),
                mock(AdminCompanionDeviceService.class), migrationAuditService);
    }
}
