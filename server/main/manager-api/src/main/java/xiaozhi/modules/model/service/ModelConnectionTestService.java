package xiaozhi.modules.model.service;

import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;

public interface ModelConnectionTestService {
    CompanionModelTestVO test(String modelType, String providerCode, String id, ModelConfigBodyDTO body);
}
