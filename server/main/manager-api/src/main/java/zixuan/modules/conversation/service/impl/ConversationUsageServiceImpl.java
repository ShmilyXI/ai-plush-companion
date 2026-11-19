package zixuan.modules.conversation.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.modules.conversation.dao.ConversationUsageDao;
import zixuan.modules.conversation.entity.ConversationUsageEntity;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.service.ConversationUsageService;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import zixuan.modules.sys.service.SysParamsService;

@Service
@RequiredArgsConstructor
public class ConversationUsageServiceImpl implements ConversationUsageService {
    public static final String DAILY_LIMIT_PARAM = "app.daily_turn_limit";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAYS = 90;

    private final ConversationUsageDao usageDao;
    private final SysParamsService sysParamsService;

    @Override
    public int remainingToday(Long userId) {
        int limit = dailyLimit();
        if (limit <= 0 || userId == null) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, limit - usedToday(userId));
    }

    @Override
    public int requireTurnBudget(Long userId) {
        int remaining = remainingToday(userId);
        if (remaining <= 0) {
            throw new RenException(ErrorCode.DAILY_TURN_LIMIT_REACHED);
        }
        return Math.min(remaining, SESSION_TURN_LIMIT);
    }

    @Override
    public Integer record(PublicConversationRuntimeBundleVO bundle, Map<String, ?> item) {
        if (bundle == null || bundle.ownerId() == null || item == null) {
            return null;
        }
        String turnId = text(item.get("turn_id"));
        if (StringUtils.isBlank(turnId) || turnId.length() > 64) {
            return null;
        }
        boolean firstParty = bundle.config() != null
                && Boolean.TRUE.equals(bundle.config().get(ConversationThreadService.ENABLED_KEY));
        Map<?, ?> usage = item.get("usage") instanceof Map<?, ?> map ? map : Map.of();
        LocalDate today = LocalDate.now(ZONE);

        ConversationUsageEntity entity = new ConversationUsageEntity();
        entity.setUsageDate(today);
        entity.setUserId(bundle.ownerId());
        entity.setAgentId(bundle.agentId());
        entity.setConversationId(bundle.conversationId());
        entity.setTurnId(turnId);
        entity.setFirstParty(firstParty ? 1 : 0);
        entity.setInputMode("audio".equals(text(usage.get("input_mode"))) ? "audio" : "text");
        entity.setLlmModel(StringUtils.left(text(usage.get("llm_model")), 128));
        entity.setPromptTokens(count(usage.get("prompt_tokens")));
        entity.setCompletionTokens(count(usage.get("completion_tokens")));
        entity.setInputChars(orZero(count(usage.get("input_chars"))));
        entity.setReplyChars(orZero(count(usage.get("reply_chars"))));
        entity.setTtsChars(orZero(count(usage.get("tts_chars"))));
        entity.setFirstTextMs(count(usage.get("first_text_ms")));
        entity.setTotalMs(count(usage.get("total_ms")));
        entity.setCreatedAt(new Date());
        try {
            usageDao.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // 运行时重试时可能重复上报同一轮，只算一次。
            return firstParty ? remainingOrNull(bundle.ownerId()) : null;
        }
        return firstParty ? remainingOrNull(bundle.ownerId()) : null;
    }

    @Override
    public List<Map<String, Object>> dailySummary(int days) {
        int safeDays = Math.min(Math.max(days, 1), MAX_DAYS);
        return usageDao.dailySummary(LocalDate.now(ZONE).minusDays(safeDays - 1L));
    }

    @Override
    public List<Map<String, Object>> topUsers(String date, int limit) {
        LocalDate day;
        try {
            day = StringUtils.isBlank(date) ? LocalDate.now(ZONE) : LocalDate.parse(date.trim());
        } catch (DateTimeParseException exception) {
            throw new RenException("日期格式应为 2026-11-20 这样的形式");
        }
        return usageDao.topUsers(day, Math.min(Math.max(limit, 1), 200));
    }

    private Integer remainingOrNull(Long userId) {
        int remaining = remainingToday(userId);
        return remaining == Integer.MAX_VALUE ? null : remaining;
    }

    /**
     * 今天已经用了多少轮。直接从用量表里数：每人每天最多几百行，走索引很快，
     * 而且不依赖缓存，清空 Redis 不会让额度被重置。
     */
    private long usedToday(Long userId) {
        return usageDao.countFirstPartyTurns(userId, LocalDate.now(ZONE));
    }

    private int dailyLimit() {
        String value = sysParamsService.getValue(DAILY_LIMIT_PARAM, true);
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String text(Object value) {
        return value == null ? null : StringUtils.trimToNull(String.valueOf(value));
    }

    private Integer count(Object value) {
        if (value instanceof Number number) {
            long rounded = number.longValue();
            return rounded < 0 ? null : (int) Math.min(rounded, Integer.MAX_VALUE);
        }
        return null;
    }

    private int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
