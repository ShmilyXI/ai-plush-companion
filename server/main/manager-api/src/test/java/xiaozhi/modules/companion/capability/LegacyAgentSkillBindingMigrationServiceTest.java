package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.init.LegacyAgentSkillBindingMigrationService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class LegacyAgentSkillBindingMigrationServiceTest {
    private final AgentDao agents = mock(AgentDao.class);
    private final DeviceDao devices = mock(DeviceDao.class);
    private final DeviceSkillMappingDao legacy = mock(DeviceSkillMappingDao.class);
    private final AgentVersionSkillBindingDao bindings = mock(AgentVersionSkillBindingDao.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final LegacyAgentSkillBindingMigrationService service =
            new LegacyAgentSkillBindingMigrationService(agents, devices, legacy, bindings, audit);

    @Test
    void projectsEquivalentDeviceRowsOnce() {
        AgentEntity agent = agent();
        DeviceEntity device = device("device-1");
        DeviceSkillMappingEntity row = row("skill-weather", "LATEST", null);
        when(agents.selectList(any())).thenReturn(List.of(agent));
        when(devices.selectByAgentId("agent-1")).thenReturn(List.of(device));
        when(legacy.selectEnabledByDevice("device-1")).thenReturn(List.of(row));
        when(bindings.selectByAgentVersionAndSkill("agent-1", 2, "skill-weather")).thenReturn(null);
        when(bindings.insert(any(AgentVersionSkillBindingEntity.class))).thenReturn(1);

        var report = service.migrate();

        assertEquals(1, report.projected());
        assertEquals(0, report.conflicts());
        verify(bindings).insert(any(AgentVersionSkillBindingEntity.class));
    }

    @Test
    void recordsConflictWhenDevicesHaveDifferentBindings() {
        AgentEntity agent = agent();
        when(agents.selectList(any())).thenReturn(List.of(agent));
        when(devices.selectByAgentId("agent-1")).thenReturn(List.of(device("device-1"), device("device-2")));
        when(legacy.selectEnabledByDevice("device-1")).thenReturn(List.of(row("skill-weather", "LATEST", null)));
        when(legacy.selectEnabledByDevice("device-2")).thenReturn(List.of(row("skill-news", "LATEST", null)));

        var report = service.migrate();

        assertEquals(0, report.projected());
        assertEquals(1, report.conflicts());
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    private AgentEntity agent() {
        AgentEntity result = new AgentEntity();
        result.setId("agent-1");
        result.setUserId(7L);
        result.setActiveVersionNo(2);
        return result;
    }

    private DeviceEntity device(String id) {
        DeviceEntity result = new DeviceEntity();
        result.setId(id);
        result.setAgentId("agent-1");
        return result;
    }

    private DeviceSkillMappingEntity row(String skillId, String mode, Integer fixedVersion) {
        DeviceSkillMappingEntity result = new DeviceSkillMappingEntity();
        result.setId(1L);
        result.setSkillId(skillId);
        result.setVersionMode(mode);
        result.setFixedVersion(fixedVersion);
        result.setEnabled(1);
        result.setTriggerPriority(10);
        return result;
    }
}
