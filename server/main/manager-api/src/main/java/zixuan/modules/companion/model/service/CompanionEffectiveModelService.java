package zixuan.modules.companion.model.service;

import java.util.List;
import java.util.Map;

import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.model.vo.CompanionEffectiveModelVO;
import zixuan.modules.companion.model.vo.CompanionModelOptionVO;
import zixuan.modules.companion.model.vo.CompanionRuntimeModel;

public interface CompanionEffectiveModelService {
    List<CompanionEffectiveModelVO> resolveForDisplay(Long userId, AgentEntity profile);
    List<CompanionModelOptionVO> options(Long userId);
    Map<String, CompanionRuntimeModel> resolveRuntime(Long userId, AgentEntity profile);

    Map<String, CompanionRuntimeModel> resolveRuntimeForPlayground(Long userId, AgentEntity profile,
            Map<String, String> selectedModelIds);
}
