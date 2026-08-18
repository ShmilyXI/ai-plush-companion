package xiaozhi.modules.companion.capability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.service.CapabilityMigrationAuditService;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.companion.capability.init.LegacySkillPackageMigrationService;
import xiaozhi.modules.companion.service.CompanionAuditService;

class LegacySkillPackageMigrationServiceTest {

    @Test
    void migratesEveryHistoricalVersionWithoutChangingBindings() {
        CapabilityDao capabilities = mock(CapabilityDao.class);
        CapabilityVersionDao versions = mock(CapabilityVersionDao.class);
        SkillPackageService packages = mock(SkillPackageService.class);
        CompanionAuditService audit = mock(CompanionAuditService.class);
        CapabilityEntity skill = new CapabilityEntity();
        skill.setId("skill-weather");
        skill.setType("SKILL");
        CapabilityVersionEntity first = version(1);
        CapabilityVersionEntity second = version(2);
        when(capabilities.selectList(any(QueryWrapper.class))).thenReturn(List.of(skill));
        when(versions.selectList(any(QueryWrapper.class))).thenReturn(List.of(first, second));
        when(packages.selectVersion("skill-weather", 1)).thenReturn(null);
        when(packages.selectVersion("skill-weather", 2)).thenReturn(null);

        LegacySkillPackageMigrationService migration = new LegacySkillPackageMigrationService(
                capabilities, versions, packages, audit);

        migration.migrate();

        verify(packages).importLegacyPublished(0L, "skill-weather", 1, first.getContentJson(), first.getPublisher(),
                first.getPublishedAt());
        verify(packages).importLegacyPublished(0L, "skill-weather", 2, second.getContentJson(), second.getPublisher(),
                second.getPublishedAt());
        verify(audit).record(eq(0L), isNull(), eq("capability.skill_package_migration.completed"), eq("capability"),
                eq("skill-weather"), any());
    }

    @Test
    void rerunDoesNotDuplicatePackages() {
        CapabilityDao capabilities = mock(CapabilityDao.class);
        CapabilityVersionDao versions = mock(CapabilityVersionDao.class);
        SkillPackageService packages = mock(SkillPackageService.class);
        CompanionAuditService audit = mock(CompanionAuditService.class);
        CapabilityEntity skill = new CapabilityEntity();
        skill.setId("skill-weather");
        skill.setType("SKILL");
        CapabilityVersionEntity first = version(1);
        when(capabilities.selectList(any(QueryWrapper.class))).thenReturn(List.of(skill));
        when(versions.selectList(any(QueryWrapper.class))).thenReturn(List.of(first));
        SkillPackageEntity existing = new SkillPackageEntity();
        when(packages.selectVersion("skill-weather", 1)).thenReturn(existing);

        LegacySkillPackageMigrationService migration = new LegacySkillPackageMigrationService(
                capabilities, versions, packages, audit);

        migration.migrate();
        migration.migrate();

        verify(packages, never()).importLegacyPublished(any(), any(), eq(1), any(), any(), any());
    }

    private CapabilityVersionEntity version(int number) {
        CapabilityVersionEntity result = new CapabilityVersionEntity();
        result.setCapabilityId("skill-weather");
        result.setVersionNo(number);
        result.setContentJson("{\"id\":\"skill-weather\",\"name\":\"天气\","
                + "\"executionPrompt\":\"调用天气工具\",\"semanticThreshold\":0.7,"
                + "\"responseMode\":\"LLM\",\"timeoutMs\":30000,\"triggers\":[],"
                + "\"tools\":[{\"toolType\":\"PLUGIN\",\"toolRefId\":\"plugin-weather\","
                + "\"toolName\":\"get_weather\",\"defaultParams\":{},\"required\":true}]}");
        result.setPublisher(7L);
        result.setPublishedAt(new java.util.Date(1000L * number));
        return result;
    }
}
