package xiaozhi.modules.companion.capability.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilityVersionDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.vo.CapabilityRoutePreviewVO;

@Service
@AllArgsConstructor
public class CapabilityRoutePreviewServiceImpl implements CapabilityRoutePreviewService {
    private final DeviceSkillMappingDao mappingDao;
    private final CapabilityDao capabilityDao;
    private final CapabilityVersionDao versionDao;

    @Override
    public CapabilityRoutePreviewVO preview(String deviceId, String utterance) {
        if (StringUtils.isAnyBlank(deviceId, utterance)) throw new RenException("设备和用户输入不能为空");
        List<BoundSkill> skills = load(deviceId);
        List<Match> matches = skills.stream().flatMap(skill -> matches(skill, utterance).stream())
                .sorted(Comparator.comparingLong(Match::score).reversed().thenComparing(Match::skillId))
                .toList();
        List<String> deterministic = matches.stream().map(Match::skillId).distinct().toList();
        String selected = winner(matches);
        List<String> eligible = selected != null ? List.of(selected)
                : deterministic.isEmpty() ? skills.stream().map(BoundSkill::id).toList() : deterministic;

        Set<String> tools = new LinkedHashSet<>();
        for (String skillId : eligible) {
            skills.stream().filter(skill -> skill.id().equals(skillId)).findFirst()
                    .ifPresent(skill -> tools.addAll(skill.tools()));
        }
        CapabilityRoutePreviewVO result = new CapabilityRoutePreviewVO();
        result.setDeterministicMatches(deterministic);
        result.setSemanticRequired(selected == null);
        result.setEligibleSkillIds(eligible);
        result.setSelectedSkillId(selected);
        result.setAllowedTools(List.copyOf(tools));
        return result;
    }

    private List<BoundSkill> load(String deviceId) {
        List<DeviceSkillMappingEntity> mappings = mappingDao.selectEnabledByDevice(deviceId);
        if (mappings == null) return List.of();
        List<BoundSkill> result = new ArrayList<>();
        for (DeviceSkillMappingEntity mapping : mappings) {
            CapabilityEntity capability = capabilityDao.selectById(mapping.getSkillId());
            if (capability == null || !"SKILL".equals(capability.getType())
                    || !"PUBLISHED".equals(capability.getStatus())) continue;
            Integer versionNo = "FIXED".equalsIgnoreCase(mapping.getVersionMode())
                    ? mapping.getFixedVersion() : capability.getPublishedVersion();
            if (versionNo == null) continue;
            CapabilityVersionEntity version = versionDao.selectVersion(capability.getId(), versionNo);
            if (version == null) continue;
            Map<String, Object> content = JsonUtils.parseMap(version.getContentJson());
            result.add(new BoundSkill(capability.getId(), value(mapping.getTriggerPriority()),
                    maps(content.get("triggers")), toolNames(content.get("tools"))));
        }
        return result;
    }

    private List<Match> matches(BoundSkill skill, String utterance) {
        List<Match> result = new ArrayList<>();
        for (Map<String, Object> trigger : skill.triggers()) {
            if (Boolean.FALSE.equals(trigger.get("enabled"))) continue;
            String type = text(trigger.get("type")).toUpperCase(Locale.ROOT);
            String pattern = text(trigger.get("value"));
            boolean caseSensitive = Boolean.TRUE.equals(trigger.get("caseSensitive"));
            int length = switch (type) {
                case "KEYWORD" -> keywordLength(utterance, pattern, caseSensitive);
                case "REGEX" -> regexLength(utterance, pattern, caseSensitive);
                default -> 0;
            };
            if (length > 0) {
                long score = value(trigger.get("priority")) * 1_000_000L
                        + length * 1_000L + skill.bindingPriority();
                result.add(new Match(skill.id(), score));
            }
        }
        return result;
    }

    private String winner(List<Match> matches) {
        if (matches.isEmpty()) return null;
        long top = matches.get(0).score();
        List<String> winners = matches.stream().filter(match -> match.score() == top)
                .map(Match::skillId).distinct().toList();
        return winners.size() == 1 ? winners.get(0) : null;
    }

    private int keywordLength(String utterance, String keyword, boolean caseSensitive) {
        if (keyword.isBlank()) return 0;
        String source = caseSensitive ? utterance : utterance.toLowerCase(Locale.ROOT);
        String target = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
        return source.contains(target) ? keyword.length() : 0;
    }

    private int regexLength(String utterance, String expression, boolean caseSensitive) {
        if (expression.isBlank()) return 0;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            var matcher = Pattern.compile(expression, flags).matcher(utterance);
            return matcher.find() ? Math.max(1, matcher.end() - matcher.start()) : 0;
        } catch (PatternSyntaxException ignored) {
            return 0;
        }
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, field) -> normalized.put(String.valueOf(key), field));
            result.add(normalized);
        }
        return result;
    }

    private List<String> toolNames(Object value) {
        return maps(value).stream().map(tool -> text(tool.get("toolName")))
                .filter(name -> !name.isBlank()).toList();
    }

    private int value(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record BoundSkill(String id, int bindingPriority, List<Map<String, Object>> triggers, List<String> tools) {
    }

    private record Match(String skillId, long score) {
    }
}
