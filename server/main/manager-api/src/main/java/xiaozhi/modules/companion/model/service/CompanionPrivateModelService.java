package xiaozhi.modules.companion.model.service;

import java.util.List;

import xiaozhi.modules.companion.model.dto.CompanionPrivateModelSaveDTO;
import xiaozhi.modules.companion.model.entity.CompanionPrivateModelEntity;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.companion.model.vo.CompanionPrivateModelVO;

public interface CompanionPrivateModelService {
    List<CompanionPrivateModelVO> list(Long userId, String modelType);
    CompanionPrivateModelVO get(Long userId, String id);
    CompanionPrivateModelVO create(Long userId, CompanionPrivateModelSaveDTO dto);
    CompanionPrivateModelVO update(Long userId, String id, CompanionPrivateModelSaveDTO dto);
    void delete(Long userId, String id);
    CompanionModelTestVO test(Long userId, String id, CompanionPrivateModelSaveDTO dto);
    CompanionPrivateModelEntity requireOwned(Long userId, String id);
}
