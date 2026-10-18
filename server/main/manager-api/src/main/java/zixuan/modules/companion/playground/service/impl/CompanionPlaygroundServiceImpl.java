package zixuan.modules.companion.playground.service.impl;

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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zixuan.modules.companion.playground.dto.PlaygroundInputDTO;
import zixuan.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import zixuan.modules.companion.playground.dto.PlaygroundVirtualDeviceDTO;
import zixuan.modules.companion.playground.service.CompanionPlaygroundService;
import zixuan.modules.companion.playground.service.CompanionPlaygroundRuntimeClient;
import zixuan.modules.companion.playground.vo.PlaygroundEventVO;
import zixuan.modules.companion.playground.vo.PlaygroundSessionVO;
import zixuan.modules.companion.service.CompanionProfileService;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.companion.model.service.CompanionEffectiveModelService;
import zixuan.modules.companion.model.vo.CompanionRuntimeModel;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.timbre.vo.TimbreDetailsVO;

@Service
public class CompanionPlaygroundServiceImpl implements CompanionPlaygroundService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final CompanionProfileService profiles;
    private final CompanionPlaygroundRuntimeClient runtime;
    private final AgentService agents;
    private final CompanionEffectiveModelService effectiveModels;
    private final TimbreService timbres;

    public CompanionPlaygroundServiceImpl() {
        this(null, null, null, null, null);
    }

    public CompanionPlaygroundServiceImpl(CompanionProfileService profiles) {
        this(profiles, null, null, null, null);
    }

    public CompanionPlaygroundServiceImpl(CompanionProfileService profiles, CompanionPlaygroundRuntimeClient runtime) {
        this(profiles, runtime, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CompanionPlaygroundServiceImpl(CompanionProfileService profiles, CompanionPlaygroundRuntimeClient runtime,
            AgentService agents, CompanionEffectiveModelService effectiveModels, TimbreService timbres) {
        this.profiles = profiles;
        this.runtime = runtime;
        this.agents = agents;
        this.effectiveModels = effectiveModels;
        this.timbres = timbres;
    }

    @Override
    public PlaygroundSessionVO create(Long userId, PlaygroundSessionCreateDTO request) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        if (profiles != null) profiles.get(userId, request.getProfileId());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("profileId", request.getProfileId());
        config.put("profileVersionId", request.getProfileVersionId());
        config.put("models", request.getModels() == null ? Map.of() : Map.copyOf(request.getModels()));
        config.put("ttsVoiceId", request.getTtsVoiceId());
        config.put("skillIds", request.getSkillIds() == null ? List.of() : List.copyOf(request.getSkillIds()));
        config.put("systemPrompt", request.getSystemPrompt());
        config.put("rolePrompt", request.getRolePrompt());
        config.put("virtualDevice", virtualDevice(request.getVirtualDevice()));
        if (agents != null && effectiveModels != null) {
            var profile = agents.getAgentById(request.getProfileId(), userId);
            String voiceId = request.getTtsVoiceId() == null ? profile.getTtsVoiceId() : request.getTtsVoiceId();
            TimbreDetailsVO timbre = timbres == null || voiceId == null ? null : timbres.get(voiceId);
            Map<String, Object> runtimeModels = new LinkedHashMap<>();
            for (Map.Entry<String, CompanionRuntimeModel> entry : effectiveModels.resolveRuntimeForPlayground(userId, profile, request.getModels()).entrySet()) {
                Map<String, Object> runtimeConfig = new LinkedHashMap<>(entry.getValue().getConfig());
                if ("TTS".equals(entry.getKey()) && timbre != null) {
                    runtimeConfig.put("private_voice", timbre.getTtsVoice());
                    if (timbre.getReferenceAudio() != null) runtimeConfig.put("ref_audio", timbre.getReferenceAudio());
                    if (timbre.getReferenceText() != null) runtimeConfig.put("ref_text", timbre.getReferenceText());
                }
                runtimeModels.put(entry.getKey(), Map.of("id", entry.getValue().getId(), "config", runtimeConfig));
            }
            config.put("runtimeModels", runtimeModels);
            config.put("profileName", profile.getAgentName());
            config.put("profilePersonality", profile.getPersonality());
            config.put("profileSystemPrompt", profile.getSystemPrompt());
        }
        String id = UUID.randomUUID().toString();
        StoredSession stored = new StoredSession(userId, id, 1L, Collections.unmodifiableMap(config), Instant.now().plus(SESSION_TTL));
        sessions.put(id, stored);
        if (runtime != null) {
            try {
                runtime.create(id, stored.snapshotVersion, stored.config);
            } catch (RuntimeException exception) {
                sessions.remove(id);
                throw exception;
            }
        }
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
        long inputSequence;
        synchronized (stored) { inputSequence = stored.nextInputSequence++; }
        List<Map<String, Object>> runtimeEvents = runtime == null ? List.of() : runtime.input(sessionId, inputSequence, input);
        List<PlaygroundEventVO> generated = new ArrayList<>();
        if (runtimeEvents.isEmpty()) {
            addEvent(stored, generated, "runtime", "execution", "", "", "failed", "虚拟运行时暂无返回", Map.of());
        } else {
            appendRuntimeEvents(stored, generated, runtimeEvents);
        }
        return generated;
    }

    private static void appendRuntimeEvents(StoredSession stored, List<PlaygroundEventVO> generated,
            List<Map<String, Object>> runtimeEvents) {
        for (Map<String, Object> value : runtimeEvents) {
            String capability = value.get("capability") instanceof String text ? text : "runtime";
            String stage = value.get("stage") instanceof String text ? text : "execution";
            String output = value.get("output_summary") instanceof String text ? text : "运行时已返回结果";
            String input = value.get("input_summary") instanceof String text ? text : "";
            String status = value.get("status") instanceof String text ? text : "completed";
            String error = value.get("error") instanceof String text ? text : null;
            Map<String, Object> details = objectMap(value.get("details"));
            addEvent(stored, generated, capability, stage, input, output, status, error, details);
        }
    }

    private static void addEvent(StoredSession stored, List<PlaygroundEventVO> generated,
            String capability, String stage, String inputSummary, String outputSummary) {
        addEvent(stored, generated, capability, stage, inputSummary, outputSummary, "completed", null, Map.of());
    }

    private static void addEvent(StoredSession stored, List<PlaygroundEventVO> generated,
            String capability, String stage, String inputSummary, String outputSummary,
            String status, String error, Map<String, Object> details) {
        long sequence;
        synchronized (stored.events) { sequence = stored.events.size() + 1L; }
        Instant now = Instant.now();
        PlaygroundEventVO event = new PlaygroundEventVO(sequence, capability, stage, status, now, now, 0L,
                bounded(inputSummary), bounded(outputSummary), error == null ? null : bounded(error), details == null ? Map.of() : details);
        synchronized (stored.events) { stored.events.add(event); }
        generated.add(event);
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
        }
        return result;
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
        if (runtime != null) runtime.close(sessionId);
        sessions.remove(stored.id);
    }

    private StoredSession owned(Long userId, String id) {
        StoredSession stored = sessions.get(id);
        if (stored == null || !stored.ownerId.equals(userId) || stored.expiresAt.isBefore(Instant.now())) {
            sessions.remove(id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操练会话不存在或已过期");
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
        private long nextInputSequence = 1L;
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
