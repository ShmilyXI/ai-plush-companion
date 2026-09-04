package zixuan.modules.conversation.service;

import java.util.List;
import java.util.Map;

public interface PublicConversationHistoryStore {
    void append(String conversationId, Map<String, ?> item);

    List<Map<String, Object>> history(String conversationId, int limit);
}
