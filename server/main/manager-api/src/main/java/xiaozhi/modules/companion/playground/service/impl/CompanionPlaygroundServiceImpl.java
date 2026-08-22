package xiaozhi.modules.companion.playground.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import xiaozhi.modules.companion.playground.dto.PlaygroundInputDTO;
import xiaozhi.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import xiaozhi.modules.companion.playground.dto.PlaygroundVirtualDeviceDTO;
import xiaozhi.modules.companion.playground.service.CompanionPlaygroundService;
import xiaozhi.modules.companion.playground.vo.PlaygroundEventVO;
import xiaozhi.modules.companion.playground.vo.PlaygroundSessionVO;

@Service
public class CompanionPlaygroundServiceImpl implements CompanionPlaygroundService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();

    @Override
    public PlaygroundSessionVO create(Long userId, PlaygroundSessionCreateDTO request) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("profileId", request.getProfileId());
        config.put("profileVersionId", request.getProfileVersionId());
        config.put("models", request.getModels() == null ? Map.of() : Map.copyOf(request.getModels()));
        config.put("ttsVoiceId", request.getTtsVoiceId());
        config.put("skillIds", request.getSkillIds() == null ? List.of() : List.copyOf(request.getSkillIds()));
        config.put("systemPrompt", request.getSystemPrompt());
        config.put("rolePrompt", request.getRolePrompt());
        config.put("virtualDevice", virtualDevice(request.getVirtualDevice()));
        String id = UUID.randomUUID().toString();
        StoredSession stored = new StoredSession(userId, id, 1L, Collections.unmodifiableMap(config), Instant.now().plus(SESSION_TTL));
        sessions.put(id, stored);
        return view(stored);
    }

    @Override
    public PlaygroundSessionVO get(Long userId, String sessionId) {
        return view(owned(userId, sessionId));
    }

    @Override
    public List<PlaygroundEventVO> acceptInput(Long userId, String sessionId, PlaygroundInputDTO input) {
        StoredSession stored = owned(userId, sessionId);
        input.validatePayload();
        String summary = switch (input.getKind()) {
            case TEXT -> input.getText();
            case AUDIO -> "音频输入";
            case VISION -> "图片输入";
            case ACTIVITY -> "活动状态";
        };
        List<PlaygroundEventVO> generated = new ArrayList<>();
        addEvent(stored, generated, input.getKind().name().toLowerCase(), "input", bounded(summary), "已接收虚拟输入");
        if (input.getKind() == xiaozhi.modules.companion.playground.dto.PlaygroundInputKind.AUDIO) {
            addEvent(stored, generated, "asr", "recognition", "音频输入", "已完成语音识别");
        }
        if (input.getKind() == xiaozhi.modules.companion.playground.dto.PlaygroundInputKind.VISION) {
            addEvent(stored, generated, "vision", "understanding", "图片输入", "已完成视觉理解");
        }
        if (input.getKind() == xiaozhi.modules.companion.playground.dto.PlaygroundInputKind.ACTIVITY) {
            addEvent(stored, generated, "activity", "sensor", "活动状态", "已接收模拟活动");
        }
        addEvent(stored, generated, "llm", "response", bounded(summary), "虚拟模型已生成回复");
        addEvent(stored, generated, "tts", "synthesis", "虚拟模型回复", "已生成试听结果");
        addEvent(stored, generated, "memory", "candidate", bounded(summary), "已生成临时记忆候选");
        return generated;
    }

    private static void addEvent(StoredSession stored, List<PlaygroundEventVO> generated,
            String capability, String stage, String inputSummary, String outputSummary) {
        long sequence;
        synchronized (stored.events) { sequence = stored.events.size() + 1L; }
        Instant now = Instant.now();
        PlaygroundEventVO event = new PlaygroundEventVO(sequence, capability, stage, "completed", now, now, 0L,
                bounded(inputSummary), bounded(outputSummary), null);
        synchronized (stored.events) { stored.events.add(event); }
        generated.add(event);
    }

    @Override
    public List<PlaygroundEventVO> events(Long userId, String sessionId, long after) {
        StoredSession stored = owned(userId, sessionId);
        synchronized (stored.events) {
            return stored.events.stream().filter(item -> item.sequence() > after).toList();
        }
    }

    @Override
    public void close(Long userId, String sessionId) {
        StoredSession stored = owned(userId, sessionId);
        sessions.remove(stored.id);
    }

    private StoredSession owned(Long userId, String id) {
        StoredSession stored = sessions.get(id);
        if (stored == null || !stored.ownerId.equals(userId) || stored.expiresAt.isBefore(Instant.now())) {
            sessions.remove(id);
            throw new IllegalArgumentException("操练会话不存在或已过期");
        }
        return stored;
    }

    private PlaygroundSessionVO view(StoredSession stored) {
        return new PlaygroundSessionVO(stored.id, stored.snapshotVersion, stored.config,
                "/companion/playground/sessions/" + stored.id + "/events", stored.expiresAt,
                List.copyOf(stored.events));
    }

    private static Map<String, Object> virtualDevice(PlaygroundVirtualDeviceDTO value) {
        PlaygroundVirtualDeviceDTO device = value == null ? new PlaygroundVirtualDeviceDTO() : value;
        return Map.of("width", device.getWidth(), "height", device.getHeight(), "depth", device.getDepth(),
                "orientation", device.getOrientation(), "screen", device.isScreen(), "camera", device.isCamera(),
                "microphone", device.isMicrophone(), "activitySensor", device.isActivitySensor());
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= 400 ? value : value.substring(0, 400);
    }

    private static final class StoredSession {
        private final Long ownerId;
        private final String id;
        private final long snapshotVersion;
        private final Map<String, Object> config;
        private final Instant expiresAt;
        private final List<PlaygroundEventVO> events = new ArrayList<>();

        private StoredSession(Long ownerId, String id, long snapshotVersion, Map<String, Object> config, Instant expiresAt) {
            this.ownerId = ownerId;
            this.id = id;
            this.snapshotVersion = snapshotVersion;
            this.config = config;
            this.expiresAt = expiresAt;
        }
    }
}
