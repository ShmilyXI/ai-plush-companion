package xiaozhi.modules.companion.playground.service;

import java.util.List;

import xiaozhi.modules.companion.playground.dto.PlaygroundInputDTO;
import xiaozhi.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import xiaozhi.modules.companion.playground.vo.PlaygroundEventVO;
import xiaozhi.modules.companion.playground.vo.PlaygroundSessionVO;

public interface CompanionPlaygroundService {
    PlaygroundSessionVO create(Long userId, PlaygroundSessionCreateDTO request);
    PlaygroundSessionVO get(Long userId, String sessionId);
    List<PlaygroundEventVO> acceptInput(Long userId, String sessionId, PlaygroundInputDTO input);
    List<PlaygroundEventVO> events(Long userId, String sessionId, long after);
    void close(Long userId, String sessionId);
}
