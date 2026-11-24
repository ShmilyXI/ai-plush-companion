package zixuan.modules.conversation.service;

import java.util.List;
import java.util.Map;

import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

/**
 * 按“用户 + 角色”保存的对话记录。
 *
 * 会话只是一次连接，令牌过期、断线或刷新页面都会换一个新会话；
 * 用户和角色之间聊过什么不应该随会话消失，所以单独保存，新会话开始时取最近的若干轮当上下文。
 */
public interface ConversationThreadService {
    /** 写在运行时配置里的开关名。 */
    String ENABLED_KEY = "historyThread";

    /**
     * 这个会话的对话是否计入对话记录。只有第一方登录用户的会话才计入，
     * API Key 背后可能是很多不同的终端用户，不能合并成一条记录。
     */
    boolean enabled(PublicConversationRuntimeBundleVO bundle);

    /** 追加一轮。会话未开启记录、内容不完整或重复提交时不做任何事。 */
    void append(PublicConversationRuntimeBundleVO bundle, Map<String, ?> item);

    /** 最近的若干轮，按时间从早到晚。 */
    default List<Map<String, Object>> recent(Long userId, String agentId, int limit) {
        return recent(userId, agentId, limit, null);
    }

    /**
     * 早于 before（毫秒时间戳）的若干轮，按时间从早到晚。before 为空时就是最近的若干轮。
     * 网页往上翻对话时用它一页一页读更早的内容。
     */
    List<Map<String, Object>> recent(Long userId, String agentId, int limit, Long before);

    /**
     * 按天汇总的对话：每天一项，从最近的一天往前。每项有这一天的日期、这一天开始的时间、轮数和第一句话。
     * 日期按用户所在时区算，offsetMinutes 是和 UTC 相差的分钟数（东八区是 480）。
     */
    List<Map<String, Object>> days(Long userId, String agentId, int offsetMinutes);

    /** 删除一个用户与一个角色的全部对话记录，返回删除的轮数。 */
    int clear(Long userId, String agentId);
}
