package xiaozhi.modules.companion.model.service;

import java.util.List;
import java.util.Map;

import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.model.vo.CompanionEffectiveModelVO;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;

public interface CompanionEffectiveModelService {
    List<CompanionEffectiveModelVO> resolveForDisplay(Long userId, AgentEntity profile);
    List<CompanionModelOptionVO> options(Long userId);
    Map<String, CompanionRuntimeModel> resolveRuntime(Long userId, AgentEntity profile);

    Map<String, CompanionRuntimeModel> resolveRuntimeForPlayground(Long userId, AgentEntity profile,
            Map<String, String> selectedModelIds);
}
