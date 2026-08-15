package xiaozhi.modules.companion.capability.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
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
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.vo.CapabilityRoutePreviewVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO.EffectiveSkillVO;

@Service
@AllArgsConstructor
public class CapabilityRoutePreviewServiceImpl implements CapabilityRoutePreviewService {
    private final DeviceCapabilityService devices;

    @Override
    public CapabilityRoutePreviewVO preview(String deviceId, String utterance) {
        if (StringUtils.isAnyBlank(deviceId, utterance)) throw new RenException("设备和用户输入不能为空");
        List<EffectiveSkillVO> skills = devices.effectiveBundle(deviceId).getSkills();
        List<Match> matches = skills.stream().flatMap(skill -> matches(skill, utterance).stream())
                .sorted(Comparator.comparingLong(Match::score).reversed().thenComparing(Match::skillId))
                .toList();
        List<String> deterministic = matches.stream().map(Match::skillId).distinct().toList();
        String selected = winner(matches);
        List<String> eligible = selected != null ? List.of(selected)
                : deterministic.isEmpty() ? skills.stream().map(EffectiveSkillVO::getId).toList() : deterministic;
        Set<String> tools = new LinkedHashSet<>();
        for (String skillId : eligible) {
            skills.stream().filter(skill -> skillId.equals(skill.getId())).findFirst()
                    .ifPresent(skill -> tools.addAll(skill.getToolNames()));
        }

        CapabilityRoutePreviewVO result = new CapabilityRoutePreviewVO();
        result.setDeterministicMatches(deterministic);
        result.setSemanticRequired(selected == null);
        result.setEligibleSkillIds(eligible);
        result.setSelectedSkillId(selected);
        result.setAllowedTools(List.copyOf(tools));
        return result;
    }

    private List<Match> matches(EffectiveSkillVO skill, String utterance) {
        List<Match> result = new ArrayList<>();
        for (Map<String, Object> trigger : skill.getTriggers()) {
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
                long score = number(trigger.get("priority")) * 1_000_000L + length * 1_000L
                        + number(skill.getBindingPriority());
                result.add(new Match(skill.getId(), score));
            }
        }
        return result;
    }

    private String winner(List<Match> matches) {
        if (matches.isEmpty()) return null;
        long score = matches.get(0).score();
        List<String> winners = matches.stream().filter(match -> match.score() == score)
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

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Match(String skillId, long score) {
    }
}
