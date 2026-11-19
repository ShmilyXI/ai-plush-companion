package zixuan.modules.conversation.service.impl;

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
        if (StringUtils.isAnyBlank(turnId, userText, replyText) || turnId.length() > 64) {
            return;
        }
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setUserId(bundle.ownerId());
        entity.setAgentId(bundle.agentId());
        entity.setConversationId(bundle.conversationId());
        entity.setTurnId(turnId);
        entity.setUserText(StringUtils.left(userText, MAX_TEXT_LENGTH));
        entity.setReplyText(StringUtils.left(replyText, MAX_TEXT_LENGTH));
        entity.setCreatedAt(new Date());
        try {
            turnDao.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // 运行时重试时可能重复提交同一轮，已经存在就不用再写。
        }
    }

    @Override
    public List<Map<String, Object>> recent(Long userId, String agentId, int limit) {
        if (userId == null || StringUtils.isBlank(agentId)) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<ConversationTurnEntity> newestFirst = turnDao.selectList(new QueryWrapper<ConversationTurnEntity>()
                .eq("user_id", userId)
                .eq("agent_id", agentId)
                .orderByDesc("id")
                .last("LIMIT " + safeLimit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConversationTurnEntity turn : newestFirst) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("turn_id", turn.getTurnId());
            item.put("text", turn.getUserText());
            item.put("reply", turn.getReplyText());
            item.put("occurred_at", turn.getCreatedAt() == null ? null : turn.getCreatedAt().getTime());
            result.add(item);
        }
        Collections.reverse(result);
        return result;
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
