package zixuan.modules.model.service;

import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.model.dto.ModelConfigBodyDTO;

public interface ModelConnectionTestService {
    CompanionModelTestVO test(String modelType, String providerCode, String id, ModelConfigBodyDTO body);
}
