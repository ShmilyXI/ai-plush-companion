package zixuan.modules.conversation.service;

import java.util.List;
import java.util.Map;

/**
 * 角色记得的关于用户的事。
 *
 * 每条是一句话，按“用户 + 角色”保存。对话服务在每轮对话后把值得记住的事交过来，
 * 下次对话开始时再取回去放进提示词。用户可以逐条查看、删除，也可以自己写一条。
 */
public interface ConversationMemoryService {
    /** 每个角色最多记多少条。超过后丢掉最早的。 */
    int MAX_ITEMS = 60;
    /** 一条最长多少字。更长的会被截断。 */
    int MAX_LENGTH = 200;
    String FROM_CONVERSATION = "conversation";
    String FROM_USER = "user";

    /** 这个角色记得的事，从早到晚。每条有 id、content、source、created_at。 */
    List<Map<String, Object>> list(Long userId, String agentId);

    /** 记下几件事。空的、重复的不记。返回新记下的条数。 */
    int remember(Long userId, String agentId, List<String> contents, String source);

    /** 忘掉一条。只能忘掉属于这个用户和这个角色的。 */
    boolean forget(Long userId, String agentId, Long id);

    /** 忘掉这个角色记得的全部，返回条数。 */
    int clear(Long userId, String agentId);
}
