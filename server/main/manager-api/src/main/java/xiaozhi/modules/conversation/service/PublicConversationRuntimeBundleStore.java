package xiaozhi.modules.conversation.service;

import java.time.Duration;

import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

public interface PublicConversationRuntimeBundleStore {
    void put(PublicConversationRuntimeBundleVO bundle, Duration ttl);

    PublicConversationRuntimeBundleVO get(String conversationId);

    void remove(String conversationId);
}
