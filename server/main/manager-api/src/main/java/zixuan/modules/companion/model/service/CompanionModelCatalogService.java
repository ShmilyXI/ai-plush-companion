package zixuan.modules.companion.model.service;

import java.util.List;

import zixuan.modules.companion.model.vo.CompanionModelCatalogItemVO;
import zixuan.modules.companion.model.vo.CompanionPrivateModelVO;

public interface CompanionModelCatalogService {
    List<CompanionModelCatalogItemVO> management(Long userId, String modelType);
    List<CompanionModelCatalogItemVO> selection(Long userId, String modelType);
    boolean isSelectable(Long userId, String globalModelId);
    CompanionPrivateModelVO copy(Long userId, String reference, String name);
}
