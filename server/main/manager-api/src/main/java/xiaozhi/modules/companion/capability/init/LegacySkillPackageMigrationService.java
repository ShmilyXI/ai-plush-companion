package xiaozhi.modules.companion.capability.init;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.common.exception.RenException;

@Service
@AllArgsConstructor
public class LegacySkillPackageMigrationService {
    private static final long SYSTEM_OPERATOR = 0L;

    private final CapabilityDao capabilityDao;
    private final CapabilityVersionDao versionDao;
    private final SkillPackageService packages;
    private final CompanionAuditService audit;

    @Transactional(rollbackFor = Exception.class)
    public void migrate() {
        List<CapabilityEntity> skills = capabilityDao.selectList(new QueryWrapper<CapabilityEntity>()
                .eq("type", "SKILL").eq("deleted", 0));
        for (CapabilityEntity skill : skills == null ? List.<CapabilityEntity>of() : skills) {
            int migrated = 0;
            int existing = 0;
            int invalid = 0;
            List<CapabilityVersionEntity> versions = versionDao.selectList(new QueryWrapper<CapabilityVersionEntity>()
                    .eq("capability_id", skill.getId()).orderByAsc("version_no"));
            for (CapabilityVersionEntity version : versions == null ? List.<CapabilityVersionEntity>of() : versions) {
                if (packages.selectVersion(skill.getId(), version.getVersionNo()) != null) {
                    existing++;
                    continue;
                }
                try {
                    packages.importLegacyPublished(SYSTEM_OPERATOR, skill.getId(), version.getVersionNo(),
                            version.getContentJson(), version.getPublisher(), version.getPublishedAt());
                    migrated++;
                } catch (RenException exception) {
                    invalid++;
                    audit.record(SYSTEM_OPERATOR, null, "capability.skill_package_migration.invalid", "capability",
                            skill.getId(), Map.of("version", version.getVersionNo(), "reason", safeMessage(exception)));
                }
            }
            recordCompleted(skill.getId(), migrated, existing, invalid);
        }
    }

    private void recordCompleted(String skillId, int migrated, int existing, int invalid) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("migrated", migrated);
        details.put("existing", existing);
        details.put("invalid", invalid);
        audit.record(SYSTEM_OPERATOR, null, "capability.skill_package_migration.completed", "capability", skillId,
                details);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
