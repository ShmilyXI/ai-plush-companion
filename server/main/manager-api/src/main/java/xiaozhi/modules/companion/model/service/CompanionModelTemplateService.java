package xiaozhi.modules.companion.model.service;

import java.util.List;

import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;

public interface CompanionModelTemplateService {
    List<CompanionModelProviderTemplateVO> list(String modelType);
    CompanionModelProviderTemplateVO require(String id, String modelType, String providerCode);
    boolean isSecretKey(String key);
}
