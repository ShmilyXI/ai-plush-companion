package zixuan.modules.conversation.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import zixuan.modules.conversation.dao.ConversationTurnDao;
import zixuan.modules.conversation.entity.ConversationTurnEntity;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@Service
@RequiredArgsConstructor
public class ConversationThreadServiceImpl implements ConversationThreadService {
    private static final int MAX_LIMIT = 100;
    /** 按天汇总时最多看这么多轮、列出这么多天。再早的内容往上翻对话时还能读到。 */
    private static final int MAX_DAY_SCAN = 5_000;
    private static final int MAX_DAYS = 60;
    private static final int PREVIEW_LENGTH = 40;
    private static final int MAX_TEXT_LENGTH = 20_000;

    private final ConversationTurnDao turnDao;

    @Override
    public boolean enabled(PublicConversationRuntimeBundleVO bundle) {
        return bundle != null && bundle.ownerId() != null && StringUtils.isNotBlank(bundle.agentId())
                && bundle.config() != null && Boolean.TRUE.equals(bundle.config().get(ENABLED_KEY));
    }

    @Override
    public void append(PublicConversationRuntimeBundleVO bundle, Map<String, ?> item) {
        if (!enabled(bundle) || item == null) {
            return;
        }
        String turnId = text(item.get("turn_id"));
        String userText = text(item.get("text"));
        String replyText = text(item.get("reply"));
        // 开场白是角色在用户打开页面时主动说的话，没有用户的话，这一栏存空字符串。
        boolean opening = Boolean.TRUE.equals(item.get("opening"));
        if (StringUtils.isAnyBlank(turnId, replyText) || turnId.length() > 64
                || (StringUtils.isBlank(userText) && !opening)) {
            return;
        }
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setUserId(bundle.ownerId());
        entity.setAgentId(bundle.agentId());
        entity.setConversationId(bundle.conversationId());
        entity.setTurnId(turnId);
        entity.setUserText(opening ? "" : StringUtils.left(userText, MAX_TEXT_LENGTH));
        entity.setReplyText(StringUtils.left(replyText, MAX_TEXT_LENGTH));
        entity.setCreatedAt(new Date());
        try {
            turnDao.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // 运行时重试时可能重复提交同一轮，已经存在就不用再写。
        }
    }

    @Override
    public List<Map<String, Object>> recent(Long userId, String agentId, int limit, Long before) {
        if (userId == null || StringUtils.isBlank(agentId)) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        QueryWrapper<ConversationTurnEntity> query = new QueryWrapper<ConversationTurnEntity>()
                .eq("user_id", userId)
                .eq("agent_id", agentId);
        if (before != null) {
            query.lt("created_at", new Date(before));
        }
        List<ConversationTurnEntity> newestFirst = turnDao.selectList(query
                .orderByDesc("id")
                .last("LIMIT " + safeLimit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConversationTurnEntity turn : newestFirst) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("turn_id", turn.getTurnId());
            item.put("text", turn.getUserText());
            item.put("reply", turn.getReplyText());
            item.put("occurred_at", turn.getCreatedAt() == null ? null : turn.getCreatedAt().getTime());
            // 没有用户那句话的是角色主动说的开场白。
            if (StringUtils.isBlank(turn.getUserText())) item.put("opening", true);
            result.add(item);
        }
        Collections.reverse(result);
        return result;
    }

    @Override
    public List<Map<String, Object>> days(Long userId, String agentId, int offsetMinutes) {
        if (userId == null || StringUtils.isBlank(agentId)) {
            return List.of();
        }
        // 时区最多差 14 小时，超出的按 UTC 算。
        ZoneOffset zone = Math.abs(offsetMinutes) <= 14 * 60 ? ZoneOffset.ofTotalSeconds(offsetMinutes * 60) : ZoneOffset.UTC;
        // 只读时间和 id，第一句话等分好天以后再读。
        List<ConversationTurnEntity> newestFirst = turnDao.selectList(new QueryWrapper<ConversationTurnEntity>()
                .select("id", "created_at")
                .eq("user_id", userId)
                .eq("agent_id", agentId)
                .orderByDesc("id")
                .last("LIMIT " + MAX_DAY_SCAN));
        Map<LocalDate, long[]> days = new LinkedHashMap<>();
        for (ConversationTurnEntity turn : newestFirst) {
            if (turn.getCreatedAt() == null) continue;
            LocalDate day = turn.getCreatedAt().toInstant().atOffset(zone).toLocalDate();
            long[] summary = days.get(day);
            if (summary == null) {
                if (days.size() >= MAX_DAYS) break;
                // [这一天最早一轮的 id, 轮数]
                days.put(day, new long[] {turn.getId(), 1});
            } else {
                summary[0] = Math.min(summary[0], turn.getId());
                summary[1] += 1;
            }
        }
        if (days.isEmpty()) {
            return List.of();
        }
        Map<Long, ConversationTurnEntity> firsts = new LinkedHashMap<>();
        for (ConversationTurnEntity turn : turnDao.selectBatchIds(days.values().stream().map(summary -> summary[0]).toList())) {
            firsts.put(turn.getId(), turn);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : days.entrySet()) {
            ConversationTurnEntity first = firsts.get(entry.getValue()[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", entry.getKey().toString());
            item.put("start", entry.getKey().atStartOfDay(zone).toInstant().toEpochMilli());
            item.put("turns", entry.getValue()[1]);
            item.put("preview", first == null ? null : preview(first));
            result.add(item);
        }
        return result;
    }

    /** 一天里第一句话：用户说的；角色主动开场的那轮用角色的话。 */
    private String preview(ConversationTurnEntity turn) {
        String text = StringUtils.isNotBlank(turn.getUserText()) ? turn.getUserText() : turn.getReplyText();
        String value = StringUtils.normalizeSpace(StringUtils.defaultString(text));
        return value.length() > PREVIEW_LENGTH ? value.substring(0, PREVIEW_LENGTH) : value;
    }

    @Override
    public int clear(Long userId, String agentId) {
        if (userId == null || StringUtils.isBlank(agentId)) {
            return 0;
        }
        return turnDao.delete(new QueryWrapper<ConversationTurnEntity>()
                .eq("user_id", userId)
                .eq("agent_id", agentId));
    }

    private String text(Object value) {
        return value == null ? null : StringUtils.trimToNull(String.valueOf(value));
    }
}
