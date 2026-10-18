package zixuan.modules.companion.playground.service;

import java.util.Map;
import java.util.List;

import zixuan.modules.companion.playground.dto.PlaygroundInputDTO;

public interface CompanionPlaygroundRuntimeClient {
    void create(String sessionId, long snapshotVersion, Map<String, Object> config);
    List<Map<String, Object>> input(String sessionId, long sequence, PlaygroundInputDTO input);
    void close(String sessionId);
}
