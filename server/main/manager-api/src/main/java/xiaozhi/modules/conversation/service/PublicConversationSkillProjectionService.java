package xiaozhi.modules.conversation.service;

import java.util.List;
import java.util.Map;

public interface PublicConversationSkillProjectionService {
    List<Map<String, Object>> project(String agentId, Integer versionNo);
}
