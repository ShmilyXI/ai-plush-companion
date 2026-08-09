package xiaozhi.modules.companion.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.sys.service.SysUserService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

class AdminResourceServiceImplTest {
    private ModelConfigService modelService;
    private SysUserService userService;
    private AgentTemplateService templateService;
    private TimbreService timbreService;
    private VoiceCloneService voiceCloneService;
    private CompanionAuditService auditService;
    private AgentDao agentDao;
    private AdminResourceServiceImpl service;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelConfigService.class);
        userService = mock(SysUserService.class);
        templateService = mock(AgentTemplateService.class);
        timbreService = mock(TimbreService.class);
        voiceCloneService = mock(VoiceCloneService.class);
        auditService = mock(CompanionAuditService.class);
        agentDao = mock(AgentDao.class);
        service = new AdminResourceServiceImpl(modelService, userService, templateService,
                timbreService, voiceCloneService, auditService, agentDao);
    }

    @Test
    void missingModelDeleteDoesNotMutateOrAudit() {
        when(modelService.selectById("missing")).thenReturn(null);

        assertThrows(RenException.class, () -> service.deleteModel(1L, "missing"));

        verify(modelService, never()).delete(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroRowModelDeleteDoesNotAudit() {
        ModelConfigEntity model = new ModelConfigEntity(); model.setId("m1");
        when(modelService.selectById("m1")).thenReturn(model);
        when(modelService.delete("m1")).thenReturn(false);

        assertThrows(RenException.class, () -> service.deleteModel(1L, "m1"));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void modelUsageReturnsProfileAndDeviceCounts() {
        when(agentDao.countProfilesUsingModel("m1")).thenReturn(3L);
        when(agentDao.countDevicesUsingModel("m1")).thenReturn(5L);

        var usage = service.modelUsage("m1");

        assertEquals(3L, usage.profileUsageCount());
        assertEquals(5L, usage.deviceUsageCount());
    }

    @Test
    void referencedModelDeleteDoesNotMutateOrAudit() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("m1");
        when(modelService.selectById("m1")).thenReturn(model);
        when(agentDao.countProfilesUsingModel("m1")).thenReturn(1L);
        when(agentDao.countDevicesUsingModel("m1")).thenReturn(2L);

        assertThrows(RenException.class, () -> service.deleteModel(1L, "m1"));

        verify(modelService, never()).delete(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingUserStatusTargetDoesNotMutateOrAudit() {
        when(userService.selectById(9L)).thenReturn(null);

        assertThrows(RenException.class, () -> service.changeUserStatus(1L, 9L, 0));

        verify(userService, never()).updateById(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unchangedUserStatusDoesNotWriteAnAudit() {
        SysUserEntity user = new SysUserEntity(); user.setId(9L); user.setStatus(1);
        when(userService.selectById(9L)).thenReturn(user);

        assertThrows(RenException.class, () -> service.changeUserStatus(1L, 9L, 1));

        verify(userService, never()).updateById(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroRowUserStatusUpdateDoesNotAudit() {
        SysUserEntity user = new SysUserEntity(); user.setId(9L); user.setStatus(1);
        when(userService.selectById(9L)).thenReturn(user);
        when(userService.updateById(any(SysUserEntity.class))).thenReturn(false);

        assertThrows(RenException.class, () -> service.changeUserStatus(1L, 9L, 0));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingTimbreAndVoiceDeletesDoNotAudit() {
        when(timbreService.selectById("t1")).thenReturn(null);
        when(voiceCloneService.selectById("v1")).thenReturn(null);

        assertThrows(RenException.class, () -> service.deleteTimbre(1L, "t1"));
        assertThrows(RenException.class, () -> service.deleteVoiceResource(1L, "v1"));

        verify(timbreService, never()).deleteById(any());
        verify(voiceCloneService, never()).deleteById(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroRowDefaultModelUpdateDoesNotAuditOrUpdateTemplates() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("m1"); model.setModelType("llm"); model.setIsDefault(0);
        when(modelService.selectById("m1")).thenReturn(model);
        when(modelService.updateById(model)).thenReturn(false);

        assertThrows(RenException.class, () -> service.setDefaultModel(1L, "m1"));

        verify(templateService, never()).updateDefaultTemplateModelId(any(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroRowModelStatusUpdateDoesNotAudit() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("m1"); model.setModelName("model"); model.setIsEnabled(1); model.setIsDefault(0);
        when(modelService.selectById("m1")).thenReturn(model);
        when(modelService.updateById(model)).thenReturn(false);

        assertThrows(RenException.class, () -> service.setModelEnabled(1L, "m1", 0));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroRowTemplateUpdateDoesNotAudit() {
        AgentTemplateEntity existing = new AgentTemplateEntity(); existing.setId("t1");
        AgentTemplateEntity update = new AgentTemplateEntity();
        update.setAgentCode("assistant"); update.setAgentName("Assistant");
        when(templateService.getById("t1")).thenReturn(existing);
        when(templateService.updateById(update)).thenReturn(false);

        assertThrows(RenException.class, () -> service.updateTemplate(1L, "t1", update));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }
}
