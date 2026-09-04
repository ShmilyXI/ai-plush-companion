package zixuan.modules.companion.model.service;

import java.util.List;

import zixuan.modules.companion.model.vo.CompanionModelProviderTemplateVO;

public interface CompanionModelTemplateService {
    List<CompanionModelProviderTemplateVO> list(String modelType);
    CompanionModelProviderTemplateVO require(String id, String modelType, String providerCode);
    boolean isSecretKey(String key);
}
