package zixuan.modules.companion.capability.service;

import zixuan.modules.companion.capability.vo.CapabilityMigrationAuditVO;

public interface CapabilityMigrationAuditService {
    CapabilityMigrationAuditVO report();

    void assertEnablementReady();
}
