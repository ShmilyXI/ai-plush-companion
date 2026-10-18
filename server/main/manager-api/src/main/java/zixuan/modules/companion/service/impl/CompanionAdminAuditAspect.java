package zixuan.modules.companion.service.impl;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import zixuan.modules.companion.controller.AdminCompanionController;
import zixuan.modules.companion.controller.AdminSystemSettingsController;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.device.controller.OTAMagController;
import zixuan.modules.security.user.SecurityUser;
import zixuan.common.utils.Result;

@Aspect
@Component
public class CompanionAdminAuditAspect {
    private static final String SUPER_ADMIN = "sys:role:superAdmin";

    private final CompanionAuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public CompanionAdminAuditAspect(CompanionAuditService auditService,
            PlatformTransactionManager transactionManager) {
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Around("@annotation(permission)")
    public Object auditLegacyAdminMutation(ProceedingJoinPoint joinPoint,
            RequiresPermissions permission) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (!isLegacyAdminMutation(method, permission)) {
            return joinPoint.proceed();
        }
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        transactionTemplate.execute(status -> {
            try {
                result[0] = joinPoint.proceed();
                if (result[0] instanceof Result<?> response && response.getCode() != 0) {
                    status.setRollbackOnly();
                } else {
                    record(joinPoint, method);
                }
            } catch (Throwable throwable) {
                failure[0] = throwable;
                status.setRollbackOnly();
            }
            return null;
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return result[0];
    }

    private boolean isLegacyAdminMutation(Method method, RequiresPermissions permission) {
        if (AdminCompanionController.class.isAssignableFrom(method.getDeclaringClass())
                || AdminSystemSettingsController.class.isAssignableFrom(method.getDeclaringClass())) {
            return false;
        }
        if (OTAMagController.class.isAssignableFrom(method.getDeclaringClass())
                && ("update".equals(method.getName()) || "delete".equals(method.getName()))) {
            return false;
        }
        boolean superAdmin = Arrays.asList(permission.value()).contains(SUPER_ADMIN);
        boolean mutation = method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
        return superAdmin && mutation;
    }

    private void record(ProceedingJoinPoint joinPoint, Method method) {
        String controller = method.getDeclaringClass().getSimpleName();
        String resourceType = controller.replace("Controller", "").toLowerCase(Locale.ROOT);
        Object target = Arrays.stream(joinPoint.getArgs())
                .filter(value -> value instanceof String || value instanceof Number)
                .findFirst().orElse(null);
        Long targetUserId = target instanceof Long value && controller.equals("AdminController") ? value : null;
        auditService.record(SecurityUser.getUserId(), targetUserId,
                "legacy." + resourceType + "." + method.getName(), resourceType,
                target == null ? null : String.valueOf(target), Map.of("method", method.getName()));
    }
}
