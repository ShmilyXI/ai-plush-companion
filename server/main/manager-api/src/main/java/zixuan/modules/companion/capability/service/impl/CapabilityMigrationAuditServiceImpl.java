package zixuan.modules.companion.capability.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import zixuan.common.exception.RenException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.capability.dao.DeviceSkillMappingDao;
import zixuan.modules.companion.capability.entity.DeviceSkillMappingEntity;
import zixuan.modules.companion.capability.service.CapabilityMigrationAuditService;
import zixuan.modules.companion.capability.vo.CapabilityMigrationAuditVO;
import zixuan.modules.companion.capability.vo.CapabilityMigrationAuditVO.UnmappedLegacyRowVO;
import zixuan.modules.companion.dao.CompanionAuditDao;
import zixuan.modules.companion.entity.CompanionAuditEntity;

@Service
@AllArgsConstructor
public class CapabilityMigrationAuditServiceImpl implements CapabilityMigrationAuditService {
    private final CompanionAuditDao auditDao;
    private AgentDao agentDao;
    private DeviceSkillMappingDao mappingDao;

    @Autowired
    public CapabilityMigrationAuditServiceImpl(CompanionAuditDao auditDao) {
        this.auditDao = auditDao;
    }

    @Autowired(required = false)
    public void setMigrationDataSources(AgentDao agentDao, DeviceSkillMappingDao mappingDao) {
        this.agentDao = agentDao;
        this.mappingDao = mappingDao;
    }

    @Override
    public CapabilityMigrationAuditVO report() {
        QueryWrapper<CompanionAuditEntity> query = new QueryWrapper<>();
        query.eq("action", "capability.migration.unmapped").orderByDesc("created_at");
        List<CompanionAuditEntity> rows = auditDao.selectList(query);
        List<UnmappedLegacyRowVO> unmapped = (rows == null ? List.<CompanionAuditEntity>of() : rows).stream()
                .map(this::toUnmapped).toList();
        CapabilityMigrationAuditVO result = new CapabilityMigrationAuditVO();
        if (agentDao != null) {
            result.setTotalAgents(agentDao.selectCount(new QueryWrapper<AgentEntity>()));
            result.setAgentsMissingInitialVersion(agentDao.selectCount(new QueryWrapper<AgentEntity>()
                    .isNull("active_version_no")));
        }
        if (mappingDao != null) {
            result.setEnabledLegacyBindings(mappingDao.selectCount(new QueryWrapper<DeviceSkillMappingEntity>()
                    .eq("enabled", 1)));
        }
        result.setProjectedLegacyBindings(countByAction("capability.migration.bound"));
        result.setRetryableFailureCount(countByAction("capability.migration.retryable"));
        result.setConflictCount(unmapped.size());
        result.setUnmappedCount(unmapped.size());
        result.setSkippedRows(Math.max(0,
                result.getEnabledLegacyBindings() - result.getProjectedLegacyBindings() - result.getConflictCount()));
        result.setUnmapped(unmapped);
        return result;
    }

    @Override
    public void assertEnablementReady() {
        CapabilityMigrationAuditVO result = report();
        if (result.getAgentsMissingInitialVersion() > 0 || result.getConflictCount() > 0
                || result.getRetryableFailureCount() > 0 || result.getSkippedRows() > 0) {
            throw new RenException("能力迁移门禁未通过: agentsMissingInitialVersion="
                    + result.getAgentsMissingInitialVersion() + ", conflicts=" + result.getConflictCount()
                    + ", skippedRows=" + result.getSkippedRows() + ", retryableFailures="
                    + result.getRetryableFailureCount());
        }
    }

    private long countByAction(String action) {
        QueryWrapper<CompanionAuditEntity> query = new QueryWrapper<>();
        query.eq("action", action);
        return auditDao.selectCount(query);
    }

    private UnmappedLegacyRowVO toUnmapped(CompanionAuditEntity source) {
        UnmappedLegacyRowVO target = new UnmappedLegacyRowVO();
        target.setLegacyMappingId(source.getResourceId());
        target.setSummary(source.getSummary());
        target.setRecordedAt(source.getCreatedAt());
        return target;
    }
}
