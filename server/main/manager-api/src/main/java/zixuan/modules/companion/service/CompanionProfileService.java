package zixuan.modules.companion.service;

import java.util.List;

import zixuan.modules.companion.dto.CompanionProfileSaveDTO;
import zixuan.modules.companion.vo.CompanionProfileVO;
import zixuan.modules.companion.model.vo.CompanionModelOptionVO;

public interface CompanionProfileService {
    List<CompanionProfileVO> list(Long userId);

    CompanionProfileVO get(Long userId, String id);
    List<CompanionModelOptionVO> modelOptions(Long userId, String id);

    String createFromTemplate(Long userId, String templateId, String name);

    String resolveForDeviceBinding(Long userId, String requestedProfileId, String templateId, String defaultName);

    void update(Long userId, String id, CompanionProfileSaveDTO dto);

    void restorePrompt(Long userId, String id);

    void delete(Long userId, String id);
}
