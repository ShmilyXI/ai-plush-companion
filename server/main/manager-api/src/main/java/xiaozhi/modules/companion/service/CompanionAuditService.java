package xiaozhi.modules.companion.service;

import java.util.Map;

import xiaozhi.common.page.PageData;
import xiaozhi.modules.companion.entity.CompanionAuditEntity;

public interface CompanionAuditService {
    void record(Long operatorId, Long targetUserId, String action, String resourceType,
            String resourceId, Map<String, ?> details);

    PageData<CompanionAuditEntity> page(int page, int limit, String keyword);
}
