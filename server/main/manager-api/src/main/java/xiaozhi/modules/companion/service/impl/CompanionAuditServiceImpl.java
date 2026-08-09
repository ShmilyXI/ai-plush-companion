package xiaozhi.modules.companion.service.impl;

import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.companion.dao.CompanionAuditDao;
import xiaozhi.modules.companion.entity.CompanionAuditEntity;
import xiaozhi.modules.companion.service.CompanionAuditService;

@Service
@AllArgsConstructor
public class CompanionAuditServiceImpl implements CompanionAuditService {
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "password", "token", "secret", "systemprompt", "memory", "memorycontent",
            "mqtt", "camera", "image", "video");
    private static final int MAX_VALUE_LENGTH = 160;
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final CompanionAuditDao auditDao;

    @Override
    public void record(Long operatorId, Long targetUserId, String action, String resourceType,
            String resourceId, Map<String, ?> details) {
        if (operatorId == null || action == null || action.isBlank()
                || resourceType == null || resourceType.isBlank()) {
            throw new RenException("审计记录缺少必要字段");
        }
        CompanionAuditEntity audit = new CompanionAuditEntity();
        audit.setOperatorId(operatorId);
        audit.setTargetUserId(targetUserId);
        audit.setAction(action);
        audit.setResourceType(resourceType);
        audit.setResourceId(resourceId);
        audit.setSummary(safeSummary(details));
        audit.setCreatedAt(new Date());
        if (auditDao.insert(audit) != 1) {
            throw new RenException("审计记录写入失败");
        }
    }

    @Override
    public PageData<CompanionAuditEntity> page(int page, int limit, String keyword) {
        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        QueryWrapper<CompanionAuditEntity> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like("action", value)
                    .or().like("resource_type", value)
                    .or().like("resource_id", value));
        }
        query.orderByDesc("created_at");
        IPage<CompanionAuditEntity> result = auditDao.selectPage(new Page<>(safePage, safeLimit), query);
        return new PageData<>(result.getRecords(), result.getTotal());
    }

    private String safeSummary(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        details.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEYS.stream().anyMatch(normalized::contains)) {
                joiner.add(key + "=***");
                return;
            }
            String value = String.valueOf(entry.getValue()).replaceAll("[\\r\\n\\t]", " ");
            if (value.length() > MAX_VALUE_LENGTH) {
                value = value.substring(0, MAX_VALUE_LENGTH) + "…";
            }
            joiner.add(key + "=" + value);
        });
        String summary = joiner.toString();
        return summary.length() <= MAX_SUMMARY_LENGTH
                ? summary
                : summary.substring(0, MAX_SUMMARY_LENGTH - 1) + "…";
    }
}
