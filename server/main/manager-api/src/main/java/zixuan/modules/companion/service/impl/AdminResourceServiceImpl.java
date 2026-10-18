package zixuan.modules.companion.service.impl;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.companion.service.AdminResourceService;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.dto.ModelConfigBodyDTO;
import zixuan.modules.model.dto.ModelConfigDTO;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.sys.entity.SysUserEntity;
import zixuan.modules.sys.service.SysUserService;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.timbre.dto.TimbreDataDTO;
import zixuan.modules.voiceclone.service.VoiceCloneService;
import cn.hutool.json.JSONObject;

@Service
@AllArgsConstructor
public class AdminResourceServiceImpl implements AdminResourceService {
    private final ModelConfigService modelService;
    private final SysUserService userService;
    private final AgentTemplateService templateService;
    private final TimbreService timbreService;
    private final VoiceCloneService voiceCloneService;
    private final CompanionAuditService auditService;
    private final AgentDao agentDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createModel(Long operatorId, String modelType, String providerCode, ModelConfigBodyDTO body) {
        ModelConfigDTO created = modelService.add(modelType, providerCode, body);
        if (created == null || created.getId() == null || created.getId().isBlank()) {
            throw new RenException("模型创建失败");
        }
        auditService.record(operatorId, null, "model.create", "model", created.getId(),
                Map.of("modelType", modelType, "modelName", body.getModelName()));
        return created.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(Long operatorId, String id, String modelName, Integer isEnabled, String remark, Integer sort,
            JSONObject configPatch) {
        ModelConfigEntity existing = modelService.selectById(id);
        if (existing == null) {
            throw new RenException("模型不存在");
        }
        modelService.editMutable(id, modelName, isEnabled, remark, sort, configPatch);
        auditService.record(operatorId, null, "model.update", "model", id,
                Map.of("modelType", existing.getModelType(), "modelName", modelName));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long operatorId, String id) {
        if (modelService.selectById(id) == null) {
            throw new RenException("模型不存在");
        }
        ModelUsage usage = modelUsage(id);
        if (usage.profileUsageCount() > 0 || usage.deviceUsageCount() > 0) {
            throw new RenException("模型已被角色或设备使用，不能删除");
        }
        if (!modelService.delete(id)) throw new RenException("模型不存在");
        auditService.record(operatorId, null, "model.delete", "model", id, Map.of());
    }

    @Override
    public ModelUsage modelUsage(String id) {
        return new ModelUsage(agentDao.countProfilesUsingModel(id), agentDao.countDevicesUsingModel(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultModel(Long operatorId, String id) {
        ModelConfigEntity model = modelService.selectById(id);
        if (model == null) {
            throw new RenException("模型不存在");
        }
        if (Integer.valueOf(1).equals(model.getIsDefault())) {
            throw new RenException("模型已经是默认配置");
        }
        modelService.setDefaultModel(model.getModelType(), 0);
        model.setConfigJson(null);
        model.setIsDefault(1);
        model.setIsEnabled(1);
        if (!modelService.updateById(model)) {
            throw new RenException("模型不存在");
        }
        templateService.updateDefaultTemplateModelId(model.getModelType(), model.getId());
        auditService.record(operatorId, null, "model.default", "model", id,
                Map.of("modelType", model.getModelType()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setModelEnabled(Long operatorId, String id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new RenException("模型状态不合法");
        }
        ModelConfigEntity model = modelService.selectById(id);
        if (model == null) {
            throw new RenException("模型配置不存在");
        }
        if (Integer.valueOf(status).equals(model.getIsEnabled())) {
            throw new RenException("模型状态未变化");
        }
        if (status == 0 && Integer.valueOf(1).equals(model.getIsDefault())) {
            throw new RenException("默认模型配置不允许关闭");
        }
        model.setConfigJson(null);
        model.setIsEnabled(status);
        if (!modelService.updateById(model)) {
            throw new RenException("模型配置不存在");
        }
        auditService.record(operatorId, null, "model.status", "model", id,
                Map.of("modelName", String.valueOf(model.getModelName()), "enabled", status));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeUserStatus(Long operatorId, Long userId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new RenException("用户状态不合法");
        }
        SysUserEntity existing = userService.selectById(userId);
        if (existing == null) {
            throw new RenException("用户不存在");
        }
        if (status.equals(existing.getStatus())) {
            throw new RenException("用户状态未变化");
        }
        SysUserEntity update = new SysUserEntity();
        update.setId(userId);
        update.setStatus(status);
        if (!userService.updateById(update)) {
            throw new RenException("用户不存在");
        }
        auditService.record(operatorId, userId, "user.status", "user", String.valueOf(userId),
                Map.of("status", status));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTimbre(Long operatorId, String id) {
        if (timbreService.selectById(id) == null || !timbreService.deleteById(id)) {
            throw new RenException("音色不存在");
        }
        auditService.record(operatorId, null, "timbre.delete", "timbre", id, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVoiceResource(Long operatorId, String id) {
        if (voiceCloneService.selectById(id) == null || !voiceCloneService.deleteById(id)) {
            throw new RenException("克隆音色不存在");
        }
        auditService.record(operatorId, null, "voice.delete", "voiceResource", id, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createTemplate(Long operatorId, AgentTemplateEntity template) {
        if (template == null || template.getAgentName() == null || template.getAgentName().isBlank()
                || template.getAgentCode() == null || template.getAgentCode().isBlank()) {
            throw new RenException("模板代码和名称不能为空");
        }
        template.setId(null);
        template.setSort(templateService.getNextAvailableSort());
        if (!templateService.save(template)) {
            throw new RenException("模板创建失败");
        }
        auditService.record(operatorId, null, "template.create", "template", template.getId(),
                Map.of("agentCode", template.getAgentCode(), "agentName", template.getAgentName()));
        return template.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(Long operatorId, String id, AgentTemplateEntity template) {
        if (template == null || template.getAgentName() == null || template.getAgentName().isBlank()) {
            throw new RenException("模板名称不能为空");
        }
        if (templateService.getById(id) == null) {
            throw new RenException("模板不存在");
        }
        template.setId(id);
        if (!templateService.updateById(template)) {
            throw new RenException("模板不存在");
        }
        auditService.record(operatorId, null, "template.update", "template", id,
                Map.of("agentCode", String.valueOf(template.getAgentCode()), "agentName", template.getAgentName()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long operatorId, String id) {
        AgentTemplateEntity template = templateService.getById(id);
        if (template == null || !templateService.removeById(id)) {
            throw new RenException("模板不存在");
        }
        templateService.reorderTemplatesAfterDelete(template.getSort());
        auditService.record(operatorId, null, "template.delete", "template", id,
                Map.of("agentName", String.valueOf(template.getAgentName())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTimbre(Long operatorId, TimbreDataDTO dto) {
        if (!timbreService.save(dto)) {
            throw new RenException("音色创建失败");
        }
        auditService.record(operatorId, null, "timbre.create", "timbre", null,
                Map.of("name", dto.getName(), "ttsModelId", dto.getTtsModelId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTimbre(Long operatorId, String id, TimbreDataDTO dto) {
        if (timbreService.selectById(id) == null || !timbreService.update(id, dto)) {
            throw new RenException("音色不存在");
        }
        auditService.record(operatorId, null, "timbre.update", "timbre", id,
                Map.of("name", dto.getName(), "ttsModelId", dto.getTtsModelId()));
    }
}
