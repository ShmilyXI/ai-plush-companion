package zixuan.modules.conversation.service;

import java.util.List;
import java.util.Map;

import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

/**
 * 对话用量和每日额度。
 *
 * 每完成一轮记一次用量。登录用户每天能对话的轮数有上限，由系统参数 app.daily_turn_limit 控制；
 * API Key 会话只记用量，不受这个上限约束，它们有自己的频率限制。
 */
public interface ConversationUsageService {
    /** 写在运行时配置里的键：这个会话最多还能进行多少轮。 */
    String TURN_BUDGET_KEY = "turnBudget";

    /** 单个会话的轮数上限，与运行时保持一致。 */
    int SESSION_TURN_LIMIT = 100;

    /**
     * 用户今天还能对话多少轮。不限制时返回 Integer.MAX_VALUE。
     */
    int remainingToday(Long userId);

    /**
     * 今天的次数用完时抛出可直接展示的错误，否则返回这个会话可以使用的轮数。
     */
    int requireTurnBudget(Long userId);

    /**
     * 记录一轮的用量。
     *
     * @return 这个用户今天还剩多少轮；不受每日上限约束的会话返回 null
     */
    Integer record(PublicConversationRuntimeBundleVO bundle, Map<String, ?> item);

    List<Map<String, Object>> dailySummary(int days);

    List<Map<String, Object>> topUsers(String date, int limit);
}
