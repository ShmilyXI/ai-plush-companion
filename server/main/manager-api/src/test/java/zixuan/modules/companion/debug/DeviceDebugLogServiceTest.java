package zixuan.modules.companion.debug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.companion.debug.model.DeviceDebugLogDraft;
import zixuan.modules.companion.debug.model.DeviceDebugLogEvent;
import zixuan.modules.companion.debug.service.DeviceDebugLogSanitizer;
import zixuan.modules.companion.debug.service.DeviceDebugLogService;
import zixuan.modules.companion.debug.service.DeviceDebugLogStore;
import zixuan.modules.companion.debug.vo.DeviceDebugLogHistoryVO;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceService;

class DeviceDebugLogServiceTest {
    private DeviceService deviceService;
    private DeviceDebugLogStore store;
    private DeviceDebugLogService service;

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        store = mock(DeviceDebugLogStore.class);
        service = new DeviceDebugLogService(
                deviceService, store, new DeviceDebugLogSanitizer(), new ObjectMapper());
    }

    @Test
    void disabledDeviceDoesNotAppendCandidateEvent() {
        when(deviceService.selectById("device-a")).thenReturn(device("device-a", 7L, 0));

        service.ingest("device-a", draft(null, null, Map.of("text", "hello"), null));

        verifyNoInteractions(store);
        verify(deviceService, never()).getDeviceByMacAddress(any());
    }

    @Test
    void enabledDeviceAppendsSanitizedAndNormalizedEvent() {
        DeviceEntity device = device("real-device-id", 7L, 1);
        when(deviceService.selectById("real-device-id")).thenReturn(device);
        when(store.append(eq("real-device-id"), any(), anyLong())).thenReturn("1-0");
        long before = System.currentTimeMillis();

        service.ingest("real-device-id", draft("   ", "", Map.of(
                "apiKey", "secret-value",
                "visible", "kept"), Long.MAX_VALUE));

        long after = System.currentTimeMillis();
        ArgumentCaptor<DeviceDebugLogEvent> saved = ArgumentCaptor.forClass(DeviceDebugLogEvent.class);
        ArgumentCaptor<Long> appendedAt = ArgumentCaptor.forClass(Long.class);
        verify(store).append(eq("real-device-id"), saved.capture(), appendedAt.capture());
        DeviceDebugLogEvent event = saved.getValue();
        assertNull(event.cursor());
        assertEquals("real-device-id", event.deviceId());
        assertTrue(event.receivedAt() >= before && event.receivedAt() <= after);
        assertEquals(event.receivedAt(), event.occurredAt());
        assertEquals(event.receivedAt(), appendedAt.getValue());
        assertNull(event.sessionId());
        assertNull(event.sentenceId());
        assertEquals("conversation", event.category());
        assertEquals("asr.completed", event.eventType());
        assertEquals("info", event.level());
        assertEquals("识别 secret 不应因值本身被删除", event.summary());
        assertEquals(Map.of("visible", "kept"), event.details());
        assertEquals(21L, event.durationMs());
    }

    @Test
    void resolvesMacOnlyAfterDeviceIdLookupMisses() {
        DeviceEntity device = device("device-from-mac", 7L, 1);
        when(deviceService.selectById("AA:BB:CC:DD:EE:FF")).thenReturn(null);
        when(deviceService.getDeviceByMacAddress("AA:BB:CC:DD:EE:FF")).thenReturn(device);

        service.ingest("AA:BB:CC:DD:EE:FF", draft(null, null, Map.of(), 1L));

        InOrder resolution = inOrder(deviceService, store);
        resolution.verify(deviceService).selectById("AA:BB:CC:DD:EE:FF");
        resolution.verify(deviceService).getDeviceByMacAddress("AA:BB:CC:DD:EE:FF");
        resolution.verify(store).append(eq("device-from-mac"), any(), anyLong());
    }

    @Test
    void missingDeviceAndAppendFailureNeverEscapeIngestion() {
        when(deviceService.selectById("missing")).thenReturn(null);
        when(deviceService.getDeviceByMacAddress("missing")).thenReturn(null);
        assertDoesNotThrow(() -> service.ingest("missing", draft(null, null, Map.of(), null)));
        verifyNoInteractions(store);

        when(deviceService.selectById("device-a")).thenReturn(device("device-a", 7L, 1));
        when(store.append(eq("device-a"), any(), anyLong())).thenThrow(new IllegalStateException("redis secret"));
        assertDoesNotThrow(() -> service.ingest("device-a", draft(null, null, Map.of(), null)));
    }

    @Test
    void foreignOwnerIsRejectedWithDeviceNotExistSemantics() {
        when(deviceService.selectById("device-a")).thenReturn(device("device-a", 8L, 1));

        RenException error;
        try (MockedStatic<MessageUtils> messages = org.mockito.Mockito.mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_NOT_EXIST)).thenReturn("not found");
            error = assertThrows(RenException.class, () -> service.requireOwned(7L, "device-a"));
        }

        assertEquals(ErrorCode.DEVICE_NOT_EXIST, error.getCode());
        verify(deviceService).selectById("device-a");
    }

    @Test
    void historyRemainsReadableWhenLoggingIsDisabledAndReturnsLastCursor() {
        DeviceEntity disabled = device("device-a", 7L, 0);
        DeviceDebugLogEvent first = event("10-0", "one");
        DeviceDebugLogEvent last = event("11-0", "two");
        when(deviceService.selectById("device-a")).thenReturn(disabled);
        when(store.history("device-a", 1000)).thenReturn(List.of(first, last));

        DeviceDebugLogHistoryVO history = service.history(7L, "device-a");

        assertEquals(List.of(first, last), history.events());
        assertEquals("11-0", history.lastCursor());
    }

    @Test
    void emptyHistoryUsesOriginCursorAndStoreFailureUsesRedisErrorSemantics() {
        when(deviceService.selectById("device-a")).thenReturn(device("device-a", 7L, 0));
        when(store.history("device-a", 1000)).thenReturn(List.of());
        assertEquals("0-0", service.history(7L, "device-a").lastCursor());

        when(store.history("device-a", 1000)).thenThrow(new IllegalStateException("redis down"));
        RenException error;
        try (MockedStatic<MessageUtils> messages = org.mockito.Mockito.mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.REDIS_ERROR)).thenReturn("redis error");
            error = assertThrows(RenException.class, () -> service.history(7L, "device-a"));
        }
        assertEquals(ErrorCode.REDIS_ERROR, error.getCode());
    }

    @Test
    void streamContinuesFromRequestedCursorAndSendsNamedJsonEvents() throws Exception {
        DeviceDebugLogEvent first = event("11-0", "one");
        DeviceDebugLogEvent second = event("12-0", "two");
        when(store.readAfter("device-a", "10-0", Duration.ofSeconds(5), 100))
                .thenReturn(List.of(first, second));
        when(store.readAfter("device-a", "12-0", Duration.ofSeconds(5), 100))
                .thenThrow(new IllegalStateException("stop test stream"));
        RecordingEmitter emitter = new RecordingEmitter();

        assertDoesNotThrow(() -> service.stream("device-a", "10-0", emitter));

        assertEquals(2, emitter.sent.size());
        assertEquals("id:11-0\nevent:debug-log\ndata:", emitter.sent.get(0).get(0));
        assertEquals("id:12-0\nevent:debug-log\ndata:", emitter.sent.get(1).get(0));
        Map<?, ?> json = new ObjectMapper().readValue(emitter.sent.get(0).get(1), Map.class);
        assertEquals("11-0", json.get("cursor"));
        assertEquals("one", json.get("summary"));
        assertTrue(emitter.completedWithError);
    }

    @Test
    void emptyStreamReadSendsHeartbeatAndClientSendFailureStopsCleanly() {
        when(store.readAfter("device-a", "20-0", Duration.ofSeconds(5), 100)).thenReturn(List.of());
        RecordingEmitter heartbeatEmitter = new RecordingEmitter();
        heartbeatEmitter.failAfter = 1;

        assertDoesNotThrow(() -> service.stream("device-a", "20-0", heartbeatEmitter));

        assertEquals(List.of(":heartbeat"), heartbeatEmitter.sent.get(0));
        assertTrue(heartbeatEmitter.completed);
        verify(store, times(2)).readAfter("device-a", "20-0", Duration.ofSeconds(5), 100);
    }

    private DeviceDebugLogDraft draft(String sessionId, String sentenceId, Map<String, Object> details,
            Long occurredAt) {
        return new DeviceDebugLogDraft(
                sessionId, sentenceId, "conversation", "asr.completed", "info",
                "识别 secret 不应因值本身被删除", details, occurredAt, 21L);
    }

    private DeviceEntity device(String id, Long userId, Integer enabled) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(userId);
        device.setDebugLogEnabled(enabled);
        return device;
    }

    private DeviceDebugLogEvent event(String cursor, String summary) {
        return new DeviceDebugLogEvent(
                cursor, "device-a", 100L, 101L, null, null,
                "device", "connection.opened", "info", summary, Map.of(), null);
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<List<String>> sent = new ArrayList<>();
        private int failAfter = Integer.MAX_VALUE;
        private boolean completed;
        private boolean completedWithError;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (sent.size() >= failAfter) {
                throw new IOException("client closed");
            }
            sent.add(parts(builder.build()));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            assertInstanceOf(RuntimeException.class, ex);
            completedWithError = true;
        }

        private List<String> parts(Set<DataWithMediaType> values) {
            List<String> result = new ArrayList<>();
            for (DataWithMediaType value : values) {
                result.add(String.valueOf(value.getData()).strip());
            }
            return result;
        }
    }
}
