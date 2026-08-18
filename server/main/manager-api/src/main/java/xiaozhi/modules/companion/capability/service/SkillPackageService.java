package xiaozhi.modules.companion.capability.service;

import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.SkillPackageDraftDTO;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.vo.SkillPackageVO;

public interface SkillPackageService {
    SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId, int version, SkillPackageDraftDTO draft);

    SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId, int version, CapabilitySaveDTO request);

    SkillPackageEntity selectDraft(String capabilityId);

    SkillPackageEntity selectVersion(String capabilityId, int version);

    SkillPackageEntity publishDraft(Long operatorId, String capabilityId);
}
