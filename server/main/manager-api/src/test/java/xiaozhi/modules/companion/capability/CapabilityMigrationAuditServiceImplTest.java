package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.dao.CompanionAuditDao;
import xiaozhi.modules.companion.entity.CompanionAuditEntity;
import xiaozhi.modules.companion.capability.service.impl.CapabilityMigrationAuditServiceImpl;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;

class CapabilityMigrationAuditServiceImplTest {

    @Test
    void reportsLegacyRowsThatCouldNotBeMappedToDevices() {
        CompanionAuditDao auditDao = mock(CompanionAuditDao.class);
        CompanionAuditEntity row = new CompanionAuditEntity();
        row.setResourceId("13");
        row.setSummary("{agentId=agent-without-device,providerCode=get_news_from_newsnow,reason=agent_has_no_device}");
        row.setCreatedAt(new Date(1234L));
        when(auditDao.selectList(any())).thenReturn(List.of(row));

        var report = new CapabilityMigrationAuditServiceImpl(auditDao).report();

        assertEquals(1, report.getUnmappedCount());
        assertEquals("13", report.getUnmapped().get(0).getLegacyMappingId());
        assertEquals(row.getSummary(), report.getUnmapped().get(0).getSummary());
        assertEquals(row.getCreatedAt(), report.getUnmapped().get(0).getRecordedAt());
    }

    @Test
    void enablementGatePassesOnlyWhenMigrationCountersAreClean() {
        CompanionAuditDao auditDao = mock(CompanionAuditDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        DeviceSkillMappingDao mappingDao = mock(DeviceSkillMappingDao.class);
        when(auditDao.selectList(any())).thenReturn(List.of());
        when(auditDao.selectCount(any())).thenReturn(1L, 0L);
        when(agentDao.selectCount(any())).thenReturn(2L, 0L);
        when(mappingDao.selectCount(any())).thenReturn(1L);

        CapabilityMigrationAuditServiceImpl service = new CapabilityMigrationAuditServiceImpl(auditDao);
        service.setMigrationDataSources(agentDao, mappingDao);

        assertDoesNotThrow(service::assertEnablementReady);
    }

    @Test
    void enablementGateBlocksMissingVersionsAndDoesNotGuessReadiness() {
        CompanionAuditDao auditDao = mock(CompanionAuditDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        DeviceSkillMappingDao mappingDao = mock(DeviceSkillMappingDao.class);
        when(auditDao.selectList(any())).thenReturn(List.of());
        when(auditDao.selectCount(any())).thenReturn(0L, 0L);
        when(agentDao.selectCount(any())).thenReturn(2L, 1L);
        when(mappingDao.selectCount(any())).thenReturn(0L);

        CapabilityMigrationAuditServiceImpl service = new CapabilityMigrationAuditServiceImpl(auditDao);
        service.setMigrationDataSources(agentDao, mappingDao);

        assertThrows(RuntimeException.class, service::assertEnablementReady);
    }
}
