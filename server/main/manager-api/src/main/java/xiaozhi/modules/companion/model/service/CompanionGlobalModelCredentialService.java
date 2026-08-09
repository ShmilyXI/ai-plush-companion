package xiaozhi.modules.companion.model.service;

import xiaozhi.modules.companion.model.dto.CompanionGlobalModelCredentialSaveDTO;
import xiaozhi.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.companion.model.vo.GlobalModelCredentialRuntime;

public interface CompanionGlobalModelCredentialService {
    CompanionGlobalModelCredentialVO get(Long userId, String globalModelId);
    CompanionGlobalModelCredentialVO save(Long userId, String globalModelId,
            CompanionGlobalModelCredentialSaveDTO dto);
    CompanionModelTestVO test(Long userId, String globalModelId, CompanionGlobalModelCredentialSaveDTO dto);
    GlobalModelCredentialRuntime runtime(Long userId, String globalModelId);
}
