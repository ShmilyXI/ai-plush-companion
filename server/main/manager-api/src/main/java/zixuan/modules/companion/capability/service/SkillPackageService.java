package zixuan.modules.companion.capability.service;

import java.util.Date;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import zixuan.modules.companion.capability.dto.CapabilitySaveDTO;
import zixuan.modules.companion.capability.dto.SkillPackageDraftDTO;
import zixuan.modules.companion.capability.entity.SkillPackageEntity;
import zixuan.modules.companion.capability.vo.SkillPackageVO;
import zixuan.modules.companion.capability.vo.SkillPackageImportVO;
import zixuan.modules.companion.capability.vo.SkillPackageValidationVO;

public interface SkillPackageService {
    SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId, int version, SkillPackageDraftDTO draft);

    SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId, int version, CapabilitySaveDTO request);

    SkillPackageEntity importLegacyPublished(Long operatorId, String capabilityId, int version,
            String contentJson, Long publisher, Date publishedAt);

    SkillPackageImportVO inspect(MultipartFile file);

    SkillPackageVO saveUploadedDraft(Long operatorId, String capabilityId, MultipartFile file);

    byte[] download(String capabilityId, int version);

    SkillPackageValidationVO draftValidation(String capabilityId);

    SkillPackageEntity selectDraft(String capabilityId);

    SkillPackageEntity selectVersion(String capabilityId, int version);

    List<SkillPackageVO> list(String capabilityId);

    SkillPackageEntity publishDraft(Long operatorId, String capabilityId);

    void deleteVersion(Long operatorId, String capabilityId, int version);
}
