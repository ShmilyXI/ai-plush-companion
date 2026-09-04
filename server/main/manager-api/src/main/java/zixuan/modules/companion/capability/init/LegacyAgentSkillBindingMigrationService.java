package zixuan.modules.companion.capability.init;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import zixuan.common.utils.JsonUtils;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.capability.dao.AgentVersionSkillBindingDao;
import zixuan.modules.companion.capability.dao.DeviceSkillMappingDao;
import zixuan.modules.companion.capability.entity.AgentVersionSkillBindingEntity;
import zixuan.modules.companion.capability.entity.DeviceSkillMappingEntity;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;

/** Projects compatible legacy device Skill rows into the owning agent version once. */
@Service
@AllArgsConstructor
public class LegacyAgentSkillBindingMigrationService {
    private static final long SYSTEM_OPERATOR = 0L;

    private final AgentDao agentDao;
    private final DeviceDao deviceDao;
    private final DeviceSkillMappingDao legacyDao;
    private final AgentVersionSkillBindingDao bindingDao;
    private final CompanionAuditService audit;

    @Transactional(rollbackFor = Exception.class)
    public MigrationReport migrate() {
        int projected = 0;
        int conflicts = 0;
        List<AgentEntity> agents = agentDao.selectList(new QueryWrapper<AgentEntity>());
        for (AgentEntity agent : agents == null ? List.<AgentEntity>of() : agents) {
            Integer versionNo = agent.getActiveVersionNo();
            if (versionNo == null) continue;
            List<DeviceEntity> devices = deviceDao.selectByAgentId(agent.getId());
            List<DeviceSkillMappingEntity> reference = null;
            boolean conflict = false;
            for (DeviceEntity device : devices == null ? List.<DeviceEntity>of() : devices) {
                List<DeviceSkillMappingEntity> rows = legacyDao.selectEnabledByDevice(device.getId());
                if (reference == null) {
                    reference = rows == null ? List.of() : rows;
                } else if (!equivalent(reference, rows)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                conflicts++;
                audit.record(SYSTEM_OPERATOR, agent.getUserId(), "capability.migration.unmapped", "agent",
                        agent.getId(), Map.of("reason", "device_skill_bindings_conflict", "versionNo", versionNo));
                continue;
            }
            for (DeviceSkillMappingEntity row : reference == null ? List.<DeviceSkillMappingEntity>of() : reference) {
                if (bindingDao.selectByAgentVersionAndSkill(agent.getId(), versionNo, row.getSkillId()) != null) continue;
                AgentVersionSkillBindingEntity target = new AgentVersionSkillBindingEntity();
                target.setAgentId(agent.getId());
                target.setVersionNo(versionNo);
                target.setSkillId(row.getSkillId());
                target.setVersionMode(row.getVersionMode());
                target.setFixedVersion(row.getFixedVersion());
                target.setOverrideJson(row.getOverrideJson());
                target.setTriggerPriority(row.getTriggerPriority());
                target.setEnabled(row.getEnabled());
                target.setMigrationSource("ai_device_skill_mapping:" + row.getId());
                target.setCreatedAt(new Date());
                target.setUpdatedAt(target.getCreatedAt());
                bindingDao.insert(target);
                projected++;
            }
        }
        return new MigrationReport(projected, conflicts);
    }

    private boolean equivalent(List<DeviceSkillMappingEntity> left, List<DeviceSkillMappingEntity> right) {
        return signature(left).equals(signature(right));
    }

    private List<String> signature(List<DeviceSkillMappingEntity> rows) {
        List<String> result = new ArrayList<>();
        for (DeviceSkillMappingEntity row : rows == null ? List.<DeviceSkillMappingEntity>of() : rows) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("skillId", row.getSkillId());
            value.put("versionMode", row.getVersionMode());
            value.put("fixedVersion", row.getFixedVersion());
            value.put("overrideJson", canonicalJson(row.getOverrideJson()));
            value.put("triggerPriority", row.getTriggerPriority());
            value.put("enabled", row.getEnabled());
            result.add(JsonUtils.toJsonString(value));
        }
        return result.stream().sorted().toList();
    }

    private String canonicalJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        try {
            return JsonUtils.toJsonString(JsonUtils.parseMap(value));
        } catch (RuntimeException exception) {
            return value;
        }
    }

    public record MigrationReport(int projected, int conflicts) {
    }
}
