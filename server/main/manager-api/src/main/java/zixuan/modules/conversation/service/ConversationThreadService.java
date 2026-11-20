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
    List<Map<String, Object>> recent(Long userId, String agentId, int limit);

    /** 删除一个用户与一个角色的全部对话记录，返回删除的轮数。 */
    int clear(Long userId, String agentId);
}
