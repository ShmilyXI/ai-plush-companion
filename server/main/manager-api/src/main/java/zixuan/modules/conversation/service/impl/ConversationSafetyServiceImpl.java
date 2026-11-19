package zixuan.modules.conversation.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.RequiredArgsConstructor;
import zixuan.common.page.PageData;
import zixuan.modules.conversation.dao.SafetyEventDao;
import zixuan.modules.conversation.entity.SafetyEventEntity;
import zixuan.modules.conversation.service.ConversationSafetyService;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import zixuan.modules.sys.service.SysParamsService;

@Service
@RequiredArgsConstructor
public class ConversationSafetyServiceImpl implements ConversationSafetyService {
    public static final String BLOCKED_TERMS_PARAM = "app.safety_blocked_terms";
    private static final Pattern TERM_SEPARATOR = Pattern.compile("[,，\\n\\r]+");
    private static final Pattern CATEGORY = Pattern.compile("^[a-z_]{1,32}$");
    private static final Set<String> DIRECTIONS = Set.of("input", "output");
    private static final Set<String> ACTIONS = Set.of("block", "crisis");
    private static final int MAX_TERMS = 500;

    private final SafetyEventDao eventDao;
    private final SysParamsService sysParamsService;

    @Override
    public List<String> blockedTerms() {
        String value = sysParamsService.getValue(BLOCKED_TERMS_PARAM, true);
        if (StringUtils.isBlank(value) || "null".equalsIgnoreCase(value.trim())) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String part : TERM_SEPARATOR.split(value)) {
            String term = part.trim();
            // 只有一个字的词太容易误伤，不接受。
            if (term.length() >= 2 && term.length() <= 64 && terms.size() < MAX_TERMS) {
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }

    @Override
    public void record(PublicConversationRuntimeBundleVO bundle, Map<String, ?> event) {
        if (bundle == null || bundle.ownerId() == null || event == null) {
            return;
        }
        String direction = text(event.get("direction"));
        String action = text(event.get("action"));
        String category = text(event.get("category"));
        if (!DIRECTIONS.contains(direction) || !ACTIONS.contains(action)
                || category == null || !CATEGORY.matcher(category).matches()) {
            return;
        }
        SafetyEventEntity entity = new SafetyEventEntity();
        entity.setUserId(bundle.ownerId());
        entity.setAgentId(bundle.agentId());
        entity.setConversationId(bundle.conversationId());
        entity.setTurnId(StringUtils.left(text(event.get("turn_id")), 64));
        entity.setDirection(direction);
        entity.setAction(action);
        entity.setCategory(category);
        entity.setCreatedAt(new Date());
        eventDao.insert(entity);
    }

    @Override
    public PageData<SafetyEventEntity> page(long page, long limit) {
        IPage<SafetyEventEntity> result = eventDao.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, limit), 100)),
                new QueryWrapper<SafetyEventEntity>().orderByDesc("id"));
        return new PageData<>(result.getRecords(), result.getTotal());
    }

    private String text(Object value) {
        return value == null ? null : StringUtils.trimToNull(String.valueOf(value));
    }
}
