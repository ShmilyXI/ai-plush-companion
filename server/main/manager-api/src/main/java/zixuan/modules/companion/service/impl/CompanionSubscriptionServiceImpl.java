package zixuan.modules.companion.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import zixuan.common.exception.RenException;
import zixuan.modules.companion.dao.CompanionPlanDao;
import zixuan.modules.companion.dao.CompanionSubscriptionDao;
import zixuan.modules.companion.entity.CompanionPlanEntity;
import zixuan.modules.companion.entity.CompanionSubscriptionEntity;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.companion.vo.CompanionEntitlementVO;
import zixuan.modules.sys.dao.SysUserDao;

@Service
public class CompanionSubscriptionServiceImpl implements CompanionSubscriptionService {
    private static final String BASIC_PLAN_CODE = "basic";

    private final CompanionPlanDao planDao;
    private final CompanionSubscriptionDao subscriptionDao;
    private final SysUserDao userDao;
    private final Clock clock;

    @Autowired
    public CompanionSubscriptionServiceImpl(CompanionPlanDao planDao, CompanionSubscriptionDao subscriptionDao,
            SysUserDao userDao) {
        this(planDao, subscriptionDao, userDao, Clock.systemUTC());
    }

    public CompanionSubscriptionServiceImpl(CompanionPlanDao planDao, CompanionSubscriptionDao subscriptionDao,
            SysUserDao userDao, Clock clock) {
        this.planDao = planDao;
        this.subscriptionDao = subscriptionDao;
        this.userDao = userDao;
        this.clock = clock;
    }

    @Override
    public CompanionEntitlementVO current(Long userId) {
        Date now = Date.from(clock.instant());
        CompanionSubscriptionEntity subscription = subscriptionDao.selectOne(
                new QueryWrapper<CompanionSubscriptionEntity>()
                        .eq("user_id", userId)
                        .eq("status", CompanionSubscriptionEntity.STATUS_ACTIVE)
                        .le("starts_at", now)
                        .and(wrapper -> wrapper.isNull("expires_at").or().gt("expires_at", now))
                        .last("LIMIT 1"));

        CompanionPlanEntity plan = subscription == null
                ? findBasicPlan()
                : planDao.selectById(subscription.getPlanId());
        if (plan == null) {
            throw new RenException("订阅套餐不存在");
        }
        return toEntitlement(plan, subscription == null ? null : subscription.getExpiresAt());
    }

    @Override
    public void requireDeviceSlot(Long userId, long currentCount) {
        if (currentCount >= current(userId).getMaxDevices()) {
            throw new RenException("设备数量已达到当前套餐上限");
        }
    }

    @Override
    public void requireProfileSlot(Long userId, long currentCount) {
        if (currentCount >= current(userId).getMaxProfiles()) {
            throw new RenException("角色数量已达到当前套餐上限");
        }
    }

    @Override
    public void requireLongTermMemory(Long userId) {
        if (!current(userId).isLongTermMemory()) {
            throw new RenException("当前套餐不支持长期记忆");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grant(Long operatorId, Long userId, String planId, Instant expiresAt) {
        if (operatorId == null || userId == null || planId == null || planId.isBlank()) {
            throw new RenException("操作人、订阅用户和套餐不能为空");
        }
        Instant now = clock.instant();
        Instant normalizedNow = now.truncatedTo(ChronoUnit.SECONDS);
        Instant normalizedExpiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.SECONDS);
        if (normalizedExpiresAt == null || !normalizedExpiresAt.isAfter(now)) {
            throw new RenException("订阅到期时间按秒计算后必须晚于当前时间");
        }
        if (userDao.selectByIdForUpdate(userId) == null) {
            throw new RenException("订阅用户不存在");
        }
        CompanionPlanEntity plan = planDao.selectByIdForUpdate(planId);
        if (plan == null || !Integer.valueOf(1).equals(plan.getStatus())) {
            throw new RenException("订阅套餐不存在或已停用");
        }

        subscriptionDao.update(null, new UpdateWrapper<CompanionSubscriptionEntity>()
                .eq("user_id", userId)
                .eq("status", CompanionSubscriptionEntity.STATUS_ACTIVE)
                .set("status", "replaced")
                .set("updated_at", Date.from(normalizedNow)));

        CompanionSubscriptionEntity subscription = new CompanionSubscriptionEntity();
        subscription.setUserId(userId);
        subscription.setPlanId(planId);
        subscription.setStatus(CompanionSubscriptionEntity.STATUS_ACTIVE);
        subscription.setStartsAt(Date.from(normalizedNow));
        subscription.setExpiresAt(Date.from(normalizedExpiresAt));
        subscription.setCreatedAt(Date.from(normalizedNow));
        subscription.setUpdatedAt(Date.from(normalizedNow));
        if (subscriptionDao.insert(subscription) != 1) {
            throw new RenException("订阅授权失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long operatorId, Long userId) {
        transition(operatorId, userId, CompanionSubscriptionEntity.STATUS_PAUSED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long operatorId, Long userId) {
        transition(operatorId, userId, CompanionSubscriptionEntity.STATUS_CANCELLED);
    }

    private void transition(Long operatorId, Long userId, String nextStatus) {
        if (operatorId == null || userId == null) {
            throw new RenException("操作人和订阅用户不能为空");
        }
        CompanionSubscriptionEntity active = subscriptionDao.selectActiveByUserIdForUpdate(userId);
        if (active == null) {
            throw new RenException("当前没有可操作的生效订阅");
        }
        CompanionSubscriptionEntity update = new CompanionSubscriptionEntity();
        update.setId(active.getId());
        update.setStatus(nextStatus);
        update.setUpdatedAt(Date.from(clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        if (subscriptionDao.updateById(update) != 1) {
            throw new RenException("订阅状态更新失败");
        }
    }

    private CompanionPlanEntity findBasicPlan() {
        return planDao.selectOne(new QueryWrapper<CompanionPlanEntity>()
                .eq("plan_code", BASIC_PLAN_CODE)
                .eq("status", 1)
                .last("LIMIT 1"));
    }

    private CompanionEntitlementVO toEntitlement(CompanionPlanEntity plan, Date expiresAt) {
        CompanionEntitlementVO vo = new CompanionEntitlementVO();
        vo.setPlanId(plan.getId());
        vo.setPlanCode(plan.getPlanCode());
        vo.setPlanName(plan.getPlanName());
        vo.setMaxDevices(plan.getMaxDevices());
        vo.setMaxProfiles(plan.getMaxProfiles());
        vo.setLongTermMemory(Integer.valueOf(1).equals(plan.getLongTermMemory()));
        vo.setAdvancedVoice(Integer.valueOf(1).equals(plan.getAdvancedVoice()));
        vo.setExpiresAt(expiresAt);
        return vo;
    }
}
