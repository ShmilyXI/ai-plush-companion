package zixuan.modules.companion.service;

import java.util.Map;

import zixuan.common.page.PageData;
import zixuan.modules.companion.entity.CompanionAuditEntity;

public interface CompanionAuditService {
    void record(Long operatorId, Long targetUserId, String action, String resourceType,
            String resourceId, Map<String, ?> details);

    PageData<CompanionAuditEntity> page(int page, int limit, String keyword);
}
