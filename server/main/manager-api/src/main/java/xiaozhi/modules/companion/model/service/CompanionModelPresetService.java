package xiaozhi.modules.companion.model.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import xiaozhi.modules.companion.model.vo.CompanionModelPresetVO;

public interface CompanionModelPresetService {
    CompanionModelPresetVO get(String globalModelId);
    Map<String, CompanionModelPresetVO> getAll(Collection<String> globalModelIds);
    Set<String> credentialKeys(String globalModelId);
}
