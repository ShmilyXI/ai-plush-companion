package zixuan.modules.companion.service;

import java.time.Instant;

import zixuan.modules.companion.vo.CompanionEntitlementVO;

public interface CompanionSubscriptionService {
    CompanionEntitlementVO current(Long userId);

    void requireDeviceSlot(Long userId, long currentCount);

    void requireProfileSlot(Long userId, long currentCount);

    void requireLongTermMemory(Long userId);

    void grant(Long operatorId, Long userId, String planId, Instant expiresAt);

    void pause(Long operatorId, Long userId);

    void cancel(Long operatorId, Long userId);
}
