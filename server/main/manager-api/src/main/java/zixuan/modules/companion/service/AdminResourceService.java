package zixuan.modules.companion.service;

import cn.hutool.json.JSONObject;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.model.dto.ModelConfigBodyDTO;
import zixuan.modules.timbre.dto.TimbreDataDTO;

public interface AdminResourceService {
    String createModel(Long operatorId, String modelType, String providerCode, ModelConfigBodyDTO body);

    void updateModel(Long operatorId, String id, String modelName, Integer isEnabled, String remark, Integer sort,
            JSONObject configPatch);

    void deleteModel(Long operatorId, String id);

    ModelUsage modelUsage(String id);

    void setDefaultModel(Long operatorId, String id);

    void setModelEnabled(Long operatorId, String id, Integer status);

    void changeUserStatus(Long operatorId, Long userId, Integer status);

    void deleteTimbre(Long operatorId, String id);

    void deleteVoiceResource(Long operatorId, String id);

    String createTemplate(Long operatorId, AgentTemplateEntity template);

    void updateTemplate(Long operatorId, String id, AgentTemplateEntity template);

    void deleteTemplate(Long operatorId, String id);

    void createTimbre(Long operatorId, TimbreDataDTO dto);

    void updateTimbre(Long operatorId, String id, TimbreDataDTO dto);

    record ModelUsage(long profileUsageCount, long deviceUsageCount) {}
}
