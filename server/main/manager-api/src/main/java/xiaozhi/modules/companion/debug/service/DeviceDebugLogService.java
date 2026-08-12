package xiaozhi.modules.companion.debug.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogDraft;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;
import xiaozhi.modules.companion.debug.vo.DeviceDebugLogHistoryVO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

@Service
@Slf4j
public class DeviceDebugLogService {
    private static final String ORIGIN_CURSOR = "0-0";
    private static final Duration STREAM_BLOCK = Duration.ofSeconds(15);
    private static final int HISTORY_LIMIT = 1_000;
    private static final int STREAM_BATCH_LIMIT = 100;
    private static final long MAX_FUTURE_SKEW_MILLIS = Duration.ofMinutes(5).toMillis();

    private final DeviceService deviceService;
    private final DeviceDebugLogStore store;
    private final DeviceDebugLogSanitizer sanitizer;
    private final ObjectMapper objectMapper;

    public DeviceDebugLogService(DeviceService deviceService, DeviceDebugLogStore store,
            DeviceDebugLogSanitizer sanitizer, ObjectMapper objectMapper) {
        this.deviceService = deviceService;
        this.store = store;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
    }

    public void ingest(String deviceRef, DeviceDebugLogDraft draft) {
        try {
            DeviceEntity device = resolveDevice(deviceRef);
            if (device == null || !Integer.valueOf(1).equals(device.getDebugLogEnabled())) {
                return;
            }
            long receivedAt = System.currentTimeMillis();
            DeviceDebugLogEvent event = new DeviceDebugLogEvent(
                    null,
                    device.getId(),
                    normalizeOccurredAt(draft.occurredAt(), receivedAt),
                    receivedAt,
                    blankToNull(draft.sessionId()),
                    blankToNull(draft.sentenceId()),
                    draft.category(),
                    draft.eventType(),
                    draft.level(),
                    sanitizer.sanitizeSummary(draft.summary()),
                    sanitizer.sanitizeDetails(draft.details()),
                    draft.durationMs());
            store.append(device.getId(), event, receivedAt);
        } catch (RuntimeException exception) {
            log.warn("设备调试事件写入失败: deviceRef={}, cause={}",
                    deviceRef, exception.getClass().getSimpleName());
        }
    }

    public DeviceEntity requireOwned(Long userId, String deviceId) {
        DeviceEntity device = deviceService.selectById(deviceId);
        if (device == null || userId == null || !userId.equals(device.getUserId())) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }
        return device;
    }

    public DeviceDebugLogHistoryVO history(Long userId, String deviceId) {
        requireOwned(userId, deviceId);
        try {
            List<DeviceDebugLogEvent> events = store.history(deviceId, HISTORY_LIMIT);
            if (events == null || events.isEmpty()) {
                return new DeviceDebugLogHistoryVO(List.of(), ORIGIN_CURSOR);
            }
            String lastCursor = events.get(events.size() - 1).cursor();
            return new DeviceDebugLogHistoryVO(events,
                    lastCursor == null || lastCursor.isBlank() ? ORIGIN_CURSOR : lastCursor);
        } catch (RuntimeException exception) {
            throw new RenException(ErrorCode.REDIS_ERROR, exception);
        }
    }

    public void stream(String deviceId, String after, SseEmitter emitter) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        emitter.onError(ignored -> stopped.set(true));
        String cursor = after == null || after.isBlank() ? ORIGIN_CURSOR : after;

        try {
            while (!stopped.get()) {
                List<DeviceDebugLogEvent> events;
                try {
                    events = store.readAfter(deviceId, cursor, STREAM_BLOCK, STREAM_BATCH_LIMIT);
                } catch (RuntimeException exception) {
                    safeCompleteWithError(emitter, exception);
                    return;
                }
                if (stopped.get()) {
                    return;
                }
                if (events == null || events.isEmpty()) {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException | IllegalStateException exception) {
                        safeComplete(emitter);
                        return;
                    }
                    continue;
                }
                for (DeviceDebugLogEvent event : events) {
                    if (stopped.get()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event()
                                .id(event.cursor())
                                .name("debug-log")
                                .data(toJson(event)));
                    } catch (IOException | IllegalStateException exception) {
                        safeComplete(emitter);
                        return;
                    } catch (RuntimeException exception) {
                        safeCompleteWithError(emitter, exception);
                        return;
                    }
                    cursor = event.cursor();
                }
            }
        } catch (RuntimeException exception) {
            safeCompleteWithError(emitter, exception);
        }
    }

    private DeviceEntity resolveDevice(String deviceRef) {
        DeviceEntity device = deviceService.selectById(deviceRef);
        return device == null ? deviceService.getDeviceByMacAddress(deviceRef) : device;
    }

    private long normalizeOccurredAt(Long occurredAt, long receivedAt) {
        if (occurredAt == null || occurredAt <= 0 || occurredAt > receivedAt + MAX_FUTURE_SKEW_MILLIS) {
            return receivedAt;
        }
        return occurredAt;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String toJson(DeviceDebugLogEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize device debug log event", exception);
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // The client may already have completed the response.
        }
    }

    private void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
            // The client may already have completed the response.
        }
    }
}
