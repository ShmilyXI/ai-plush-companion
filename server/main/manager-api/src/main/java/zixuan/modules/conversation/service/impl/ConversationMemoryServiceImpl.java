package zixuan.modules.conversation.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import zixuan.modules.conversation.dao.ConversationMemoryDao;
import zixuan.modules.conversation.entity.ConversationMemoryEntity;
import zixuan.modules.conversation.service.ConversationMemoryService;

@Service
@AllArgsConstructor
public class ConversationMemoryServiceImpl implements ConversationMemoryService {
    private final ConversationMemoryDao memoryDao;

    @Override
    public List<Map<String, Object>> list(Long userId, String agentId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationMemoryEntity entity : stored(userId, agentId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(entity.getId()));
            item.put("content", entity.getContent());
            item.put("source", entity.getSource());
            item.put("created_at", entity.getCreatedAt() == null ? null : entity.getCreatedAt().getTime());
            items.add(item);
        }
        return items;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int remember(Long userId, String agentId, List<String> contents, String source) {
        if (userId == null || StringUtils.isBlank(agentId) || contents == null || contents.isEmpty()) {
            return 0;
        }
        List<ConversationMemoryEntity> existing = stored(userId, agentId);
        Set<String> known = new HashSet<>();
        for (ConversationMemoryEntity entity : existing) {
            known.add(key(entity.getContent()));
        }
        int added = 0;
        for (String raw : contents) {
            String content = StringUtils.left(StringUtils.normalizeSpace(StringUtils.defaultString(raw)), MAX_LENGTH);
            // 一批最多记十条，免得一次异常的输出把记忆塞满。
            if (content.isEmpty() || added >= 10 || !known.add(key(content))) {
                continue;
            }
            ConversationMemoryEntity entity = new ConversationMemoryEntity();
            entity.setUserId(userId);
            entity.setAgentId(agentId);
            entity.setContent(content);
            entity.setSource(FROM_USER.equals(source) ? FROM_USER : FROM_CONVERSATION);
            entity.setCreatedAt(new Date());
            memoryDao.insert(entity);
            added++;
        }
        // 记满了就丢掉最早的，留下最近的 MAX_ITEMS 条。
        int overflow = existing.size() + added - MAX_ITEMS;
        for (int index = 0; index < overflow && index < existing.size(); index++) {
            memoryDao.deleteById(existing.get(index).getId());
        }
        return added;
    }

    @Override
    public boolean forget(Long userId, String agentId, Long id) {
        if (userId == null || StringUtils.isBlank(agentId) || id == null) {
            return false;
        }
        return memoryDao.delete(new QueryWrapper<ConversationMemoryEntity>()
                .eq("id", id).eq("user_id", userId).eq("agent_id", agentId)) > 0;
    }

    @Override
    public int clear(Long userId, String agentId) {
        if (userId == null || StringUtils.isBlank(agentId)) {
            return 0;
        }
        return memoryDao.delete(new QueryWrapper<ConversationMemoryEntity>()
                .eq("user_id", userId).eq("agent_id", agentId));
    }

    private List<ConversationMemoryEntity> stored(Long userId, String agentId) {
        if (userId == null || StringUtils.isBlank(agentId)) {
            return List.of();
        }
        List<ConversationMemoryEntity> items = memoryDao.selectList(new QueryWrapper<ConversationMemoryEntity>()
                .eq("user_id", userId).eq("agent_id", agentId).orderByAsc("id"));
        return items == null ? List.of() : items;
    }

    /** 判断两条是不是同一件事：不看空白和标点。 */
    private static String key(String content) {
        return StringUtils.defaultString(content).replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）]+", "").toLowerCase();
    }
}
