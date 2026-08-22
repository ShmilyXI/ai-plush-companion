package xiaozhi.modules.companion.playground.service;

import java.util.Map;

import xiaozhi.modules.companion.playground.dto.PlaygroundInputDTO;

public interface CompanionPlaygroundRuntimeClient {
    void create(String sessionId, long snapshotVersion, Map<String, Object> config);
    void input(String sessionId, PlaygroundInputDTO input);
    void close(String sessionId);
}
