package xiaozhi.modules.companion.capability.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import xiaozhi.modules.companion.capability.service.CapabilityMigrationAuditService;
import xiaozhi.modules.companion.capability.vo.CapabilityMigrationAuditVO;
import xiaozhi.modules.companion.capability.vo.CapabilityMigrationAuditVO.UnmappedLegacyRowVO;
import xiaozhi.modules.companion.dao.CompanionAuditDao;
import xiaozhi.modules.companion.entity.CompanionAuditEntity;

@Service
@AllArgsConstructor
public class CapabilityMigrationAuditServiceImpl implements CapabilityMigrationAuditService {
    private final CompanionAuditDao auditDao;

    @Override
    public CapabilityMigrationAuditVO report() {
        QueryWrapper<CompanionAuditEntity> query = new QueryWrapper<>();
        query.eq("action", "capability.migration.unmapped").orderByDesc("created_at");
        List<CompanionAuditEntity> rows = auditDao.selectList(query);
        List<UnmappedLegacyRowVO> unmapped = (rows == null ? List.<CompanionAuditEntity>of() : rows).stream()
                .map(this::toUnmapped).toList();
        CapabilityMigrationAuditVO result = new CapabilityMigrationAuditVO();
        result.setUnmappedCount(unmapped.size());
        result.setUnmapped(unmapped);
        return result;
    }

    private UnmappedLegacyRowVO toUnmapped(CompanionAuditEntity source) {
        UnmappedLegacyRowVO target = new UnmappedLegacyRowVO();
        target.setLegacyMappingId(source.getResourceId());
        target.setSummary(source.getSummary());
        target.setRecordedAt(source.getCreatedAt());
        return target;
    }
}
