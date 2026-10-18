package zixuan.modules.companion.capability.init;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import zixuan.common.utils.JsonUtils;
import zixuan.modules.agent.dao.AgentPluginMappingMapper;
import zixuan.modules.agent.entity.AgentPluginMapping;
import zixuan.modules.companion.capability.dao.CapabilitySecretDao;
import zixuan.modules.companion.capability.dto.DeviceSkillBindingDTO;
import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;
import zixuan.modules.companion.capability.service.CapabilitySecretService;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.vo.DeviceSkillBindingVO;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;

@Service
@AllArgsConstructor
public class LegacyPluginCapabilityMigrationService {
    private static final long SYSTEM_OPERATOR = 0L;
    private static final Map<String, Target> TARGETS = Map.of(
            "get_weather", new Target("skill-weather", "plugin-weather"),
            "get_news_from_newsnow", new Target("skill-news", "plugin-news"),
            "web_search", new Target("skill-web-search", "plugin-web-search"));

    private final AgentPluginMappingMapper legacyMappingDao;
    private final DeviceDao deviceDao;
    private final DeviceCapabilityService deviceCapabilities;
    private final CapabilitySecretService secretService;
    private final CapabilitySecretDao secretDao;
    private final CompanionAuditService audit;

    public void migrate() {
        Map<String, DevicePlan> plans = new LinkedHashMap<>();
        List<AgentPluginMapping> mappings = legacyMappingDao.selectAllWithProviderCode();
        for (AgentPluginMapping mapping : mappings == null ? List.<AgentPluginMapping>of() : mappings) {
            Target target = TARGETS.get(StringUtils.trimToEmpty(mapping.getProviderCode()));
            if (target == null) {
                recordUnmapped(mapping, "unsupported_plugin");
                continue;
            }
            List<DeviceEntity> devices = deviceDao.selectByAgentId(mapping.getAgentId());
            if (devices == null || devices.isEmpty()) {
                recordUnmapped(mapping, "agent_has_no_device");
                continue;
            }
            for (DeviceEntity device : devices) {
                plans.computeIfAbsent(device.getId(), ignored -> new DevicePlan(device, new ArrayList<>()))
                        .entries().add(new LegacyEntry(mapping, target));
            }
        }
        plans.values().forEach(this::migrateDevice);
    }

    private void migrateDevice(DevicePlan plan) {
        List<DeviceSkillBindingVO> current = deviceCapabilities.list(SYSTEM_OPERATOR, plan.device().getId(), true);
        List<DeviceSkillBindingDTO> requested = new ArrayList<>();
        Set<String> skillIds = new LinkedHashSet<>();
        for (DeviceSkillBindingVO binding : current == null ? List.<DeviceSkillBindingVO>of() : current) {
            requested.add(copy(binding));
            skillIds.add(binding.getSkillId());
        }
        boolean changed = false;
        for (LegacyEntry entry : plan.entries()) {
            if (!skillIds.add(entry.target().skillId())) continue;
            DeviceSkillBindingDTO binding = new DeviceSkillBindingDTO();
            binding.setSkillId(entry.target().skillId());
            binding.setVersionMode("LATEST");
            binding.setEnabled(true);
            binding.setTriggerPriority(0);
            binding.setOverrides(overrides(plan.device().getId(), entry));
            requested.add(binding);
            changed = true;
        }
        if (!changed) return;
        deviceCapabilities.save(SYSTEM_OPERATOR, plan.device().getId(), requested, true);
        audit.record(SYSTEM_OPERATOR, plan.device().getUserId(), "capability.migration.bound", "device",
                plan.device().getId(), Map.of("skillIds", List.copyOf(skillIds)));
    }

    private Map<String, Object> overrides(String deviceId, LegacyEntry entry) {
        Map<String, Object> params;
        try {
            params = JsonUtils.parseMap(entry.mapping().getParamInfo());
        } catch (RuntimeException exception) {
            recordUnmapped(entry.mapping(), "invalid_params");
            return Map.of();
        }
        params = params == null ? Map.of() : params;
        Map<String, Object> result = new LinkedHashMap<>();
        switch (entry.mapping().getProviderCode()) {
            case "get_weather" -> {
                put(result, "location", first(params, "location", "default_location"));
                put(result, "api_host", params.get("api_host"));
            }
            case "get_news_from_newsnow" -> put(result, "category", params.get("category"));
            case "web_search" -> {
                put(result, "provider", params.get("provider"));
                put(result, "max_results", params.get("max_results"));
            }
            default -> {
            }
        }
        String apiKey = text(params.get("api_key"));
        if (StringUtils.isNotBlank(apiKey)) {
            String secretId = secretReference(entry.target().pluginId(), deviceId, apiKey);
            if (secretId != null) result.put("api_key_secret_id", secretId);
        }
        return Map.copyOf(result);
    }

    private String secretReference(String capabilityId, String deviceId, String value) {
        String name = "legacy." + deviceId.toLowerCase(Locale.ROOT) + ".api_key";
        CapabilitySecretEntity existing = secretDao.selectByCapabilityAndName(capabilityId, name);
        if (existing != null) return existing.getId();
        secretService.save(SYSTEM_OPERATOR, capabilityId, name, value);
        CapabilitySecretEntity saved = secretDao.selectByCapabilityAndName(capabilityId, name);
        return saved == null ? null : saved.getId();
    }

    private DeviceSkillBindingDTO copy(DeviceSkillBindingVO source) {
        DeviceSkillBindingDTO target = new DeviceSkillBindingDTO();
        target.setSkillId(source.getSkillId());
        target.setVersionMode(source.getVersionMode());
        target.setFixedVersion(source.getFixedVersion());
        target.setEnabled(source.isEnabled());
        target.setOverrides(source.getOverrides());
        target.setTriggerPriority(source.getTriggerPriority());
        return target;
    }

    private void recordUnmapped(AgentPluginMapping mapping, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("agentId", mapping.getAgentId());
        details.put("pluginId", mapping.getPluginId());
        details.put("providerCode", mapping.getProviderCode());
        details.put("reason", reason);
        audit.record(SYSTEM_OPERATOR, null, "capability.migration.unmapped", "agentPluginMapping",
                String.valueOf(mapping.getId()), details);
    }

    private Object first(Map<String, Object> values, String first, String second) {
        Object value = values.get(first);
        return value == null || String.valueOf(value).isBlank() ? values.get(second) : value;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private record Target(String skillId, String pluginId) {
    }

    private record LegacyEntry(AgentPluginMapping mapping, Target target) {
    }

    private record DevicePlan(DeviceEntity device, List<LegacyEntry> entries) {
    }
}
