package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.dao.CompanionAuditDao;
import xiaozhi.modules.companion.entity.CompanionAuditEntity;
import xiaozhi.modules.companion.capability.service.impl.CapabilityMigrationAuditServiceImpl;

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
}
