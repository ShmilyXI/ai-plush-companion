package zixuan.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import zixuan.common.exception.RenException;
import zixuan.modules.companion.dao.CompanionPlanDao;
import zixuan.modules.companion.dao.CompanionSubscriptionDao;
import zixuan.modules.companion.entity.CompanionPlanEntity;
import zixuan.modules.companion.entity.CompanionSubscriptionEntity;
import zixuan.modules.companion.service.impl.CompanionSubscriptionServiceImpl;
import zixuan.modules.companion.vo.CompanionEntitlementVO;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.entity.SysUserEntity;

class CompanionSubscriptionServiceImplTest {
    private final CompanionPlanDao planDao = mock(CompanionPlanDao.class);
    private final CompanionSubscriptionDao subscriptionDao = mock(CompanionSubscriptionDao.class);
    private final SysUserDao userDao = mock(SysUserDao.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T03:00:00.900Z"), ZoneOffset.UTC);
    private final CompanionSubscriptionService service =
            new CompanionSubscriptionServiceImpl(planDao, subscriptionDao, userDao, clock);

    @Test
    void basicPlanRejectsASecondDevice() {
        stubBasicSubscription();

        assertThrows(RenException.class, () -> service.requireDeviceSlot(7L, 1));
    }

    @Test
    void currentReturnsTypedBasicEntitlements() {
        stubBasicSubscription();

        CompanionEntitlementVO entitlement = service.current(7L);

        assertEquals(1, entitlement.getMaxDevices());
        assertTrue(entitlement.isLongTermMemory());
    }

    @Test
    void disabledPlanKeepsExistingActiveSubscriptionEffective() {
        stubBasicSubscription().setStatus(0);

        CompanionEntitlementVO entitlement = service.current(7L);

        assertEquals(1, entitlement.getMaxDevices());
        assertDoesNotThrow(() -> service.requireDeviceSlot(7L, 0));
        assertDoesNotThrow(() -> service.requireLongTermMemory(7L));
    }

    @Test
    void disabledPlanCannotBeGranted() {
        when(userDao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        CompanionPlanEntity disabled = basicPlan();
        disabled.setStatus(0);
        when(planDao.selectByIdForUpdate("basic")).thenReturn(disabled);

        assertThrows(RenException.class,
                () -> service.grant(1L, 7L, "basic", Instant.parse("2026-07-29T03:00:02Z")));

        verify(subscriptionDao, never()).insert(any(CompanionSubscriptionEntity.class));
    }

    @Test
    void grantLocksTargetUserBeforePlanAndWritesSubscription() throws Exception {
        when(userDao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        when(planDao.selectByIdForUpdate("basic")).thenReturn(basicPlan());
        when(subscriptionDao.insert(any(CompanionSubscriptionEntity.class))).thenReturn(1);

        service.grant(1L, 7L, "basic", Instant.parse("2026-07-29T03:00:02Z"));

        InOrder order = inOrder(userDao, planDao, subscriptionDao);
        order.verify(userDao).selectByIdForUpdate(7L);
        order.verify(planDao).selectByIdForUpdate("basic");
        order.verify(subscriptionDao).update(any(), any());
        order.verify(subscriptionDao).insert(any(CompanionSubscriptionEntity.class));
        Method grant = CompanionSubscriptionServiceImpl.class.getMethod(
                "grant", Long.class, Long.class, String.class, Instant.class);
        assertNotNull(grant.getAnnotation(Transactional.class));
    }

    @Test
    void grantRejectsMissingTargetUserWithoutWritingSubscription() {
        when(userDao.selectByIdForUpdate(99L)).thenReturn(null);

        assertThrows(RenException.class,
                () -> service.grant(1L, 99L, "basic", Instant.parse("2026-07-29T03:00:02Z")));

        verify(planDao, never()).selectByIdForUpdate(any());
        verify(subscriptionDao, never()).insert(any(CompanionSubscriptionEntity.class));
    }

    @Test
    void grantRejectsExpiryThatFallsInCurrentDatabaseSecond() {
        when(userDao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        when(planDao.selectByIdForUpdate("basic")).thenReturn(basicPlan());

        assertThrows(RenException.class,
                () -> service.grant(1L, 7L, "basic", Instant.parse("2026-07-29T03:00:00.999Z")));

        verify(subscriptionDao, never()).insert(any(CompanionSubscriptionEntity.class));
    }

    @Test
    void grantPersistsDatesAtDatabaseSecondPrecision() {
        when(userDao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        when(planDao.selectByIdForUpdate("basic")).thenReturn(basicPlan());
        when(subscriptionDao.insert(any(CompanionSubscriptionEntity.class))).thenReturn(1);

        service.grant(1L, 7L, "basic", Instant.parse("2026-07-29T03:00:01.500Z"));

        ArgumentCaptor<CompanionSubscriptionEntity> saved =
                ArgumentCaptor.forClass(CompanionSubscriptionEntity.class);
        verify(subscriptionDao).insert(saved.capture());
        assertEquals(Instant.parse("2026-07-29T03:00:00Z"), saved.getValue().getStartsAt().toInstant());
        assertEquals(Instant.parse("2026-07-29T03:00:01Z"), saved.getValue().getExpiresAt().toInstant());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pauseAndCancelTransitionTheLockedActiveSubscriptionAndFallbackToBasic() {
        CompanionSubscriptionEntity active = activeSubscription();
        when(subscriptionDao.selectActiveByUserIdForUpdate(7L)).thenReturn(active);
        when(subscriptionDao.updateById(any(CompanionSubscriptionEntity.class))).thenReturn(1);

        service.pause(1L, 7L);

        ArgumentCaptor<CompanionSubscriptionEntity> paused = ArgumentCaptor.forClass(CompanionSubscriptionEntity.class);
        verify(subscriptionDao).updateById(paused.capture());
        assertEquals("subscription-1", paused.getValue().getId());
        assertEquals(CompanionSubscriptionEntity.STATUS_PAUSED, paused.getValue().getStatus());
        assertEquals(Instant.parse("2026-07-29T03:00:00Z"), paused.getValue().getUpdatedAt().toInstant());

        reset(subscriptionDao);
        active = activeSubscription();
        when(subscriptionDao.selectActiveByUserIdForUpdate(7L)).thenReturn(active);
        when(subscriptionDao.updateById(any(CompanionSubscriptionEntity.class))).thenReturn(1);
        service.cancel(1L, 7L);
        ArgumentCaptor<CompanionSubscriptionEntity> cancelled = ArgumentCaptor.forClass(CompanionSubscriptionEntity.class);
        verify(subscriptionDao).updateById(cancelled.capture());
        assertEquals(CompanionSubscriptionEntity.STATUS_CANCELLED, cancelled.getValue().getStatus());

        when(subscriptionDao.selectOne(any(Wrapper.class))).thenReturn(null);
        when(planDao.selectOne(any(Wrapper.class))).thenReturn(basicPlan());
        assertEquals("basic", service.current(7L).getPlanCode());
    }

    @Test
    void repeatedOrFailedLifecycleOperationsFailWithoutReportingAStateChange() {
        when(subscriptionDao.selectActiveByUserIdForUpdate(7L)).thenReturn(null);

        assertThrows(RenException.class, () -> service.pause(1L, 7L));
        assertThrows(RenException.class, () -> service.cancel(1L, 7L));
        verify(subscriptionDao, never()).updateById(any(CompanionSubscriptionEntity.class));

        when(subscriptionDao.selectActiveByUserIdForUpdate(7L)).thenReturn(activeSubscription());
        when(subscriptionDao.updateById(any(CompanionSubscriptionEntity.class))).thenReturn(0);
        assertThrows(RenException.class, () -> service.pause(1L, 7L));
    }

    @SuppressWarnings("unchecked")
    private CompanionPlanEntity stubBasicSubscription() {
        CompanionSubscriptionEntity subscription = new CompanionSubscriptionEntity();
        subscription.setUserId(7L);
        subscription.setPlanId("basic");
        subscription.setStatus(CompanionSubscriptionEntity.STATUS_ACTIVE);
        subscription.setStartsAt(new Date());
        when(subscriptionDao.selectOne(any(Wrapper.class))).thenReturn(subscription);

        CompanionPlanEntity plan = basicPlan();
        when(planDao.selectById("basic")).thenReturn(plan);
        return plan;
    }

    private CompanionPlanEntity basicPlan() {
        CompanionPlanEntity plan = new CompanionPlanEntity();
        plan.setId("basic");
        plan.setPlanCode("basic");
        plan.setPlanName("Basic");
        plan.setMaxDevices(1);
        plan.setMaxProfiles(3);
        plan.setLongTermMemory(1);
        plan.setAdvancedVoice(0);
        plan.setStatus(1);
        return plan;
    }

    private CompanionSubscriptionEntity activeSubscription() {
        CompanionSubscriptionEntity subscription = new CompanionSubscriptionEntity();
        subscription.setId("subscription-1");
        subscription.setUserId(7L);
        subscription.setPlanId("pro");
        subscription.setStatus(CompanionSubscriptionEntity.STATUS_ACTIVE);
        return subscription;
    }
}
