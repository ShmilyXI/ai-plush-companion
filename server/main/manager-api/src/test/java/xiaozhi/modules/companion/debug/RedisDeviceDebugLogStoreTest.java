package xiaozhi.modules.companion.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import xiaozhi.common.redis.RedisKeys;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;
import xiaozhi.modules.companion.debug.service.RedisDeviceDebugLogStore;
import xiaozhi.modules.security.config.WebMvcConfig;

class RedisDeviceDebugLogStoreTest {
    private static final String DEVICE_ID = "device-1";
    private static final String KEY = "device:debug:logs:" + DEVICE_ID;

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, Object, Object> streamOperations;
    private ObjectMapper objectMapper;
    private RedisDeviceDebugLogStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        objectMapper = new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        store = new RedisDeviceDebugLogStore(redisTemplate, objectMapper);
    }

    @Test
    void appendsAndRetainsTheStreamAtomically() throws Exception {
        long now = 1_800_000_000_000L;
        DeviceDebugLogEvent event = event(null, "new");
        String payload = objectMapper.writeValueAsString(event);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), any(Object[].class)))
                .thenReturn("1800000000000-0");

        String cursor = store.append(DEVICE_ID, event, now);

        assertEquals("1800000000000-0", cursor);
        ArgumentCaptor<RedisScript<String>> scriptCaptor = redisScriptCaptor();
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(scriptCaptor.capture(), eq(List.of(KEY)), argumentsCaptor.capture());
        String script = scriptCaptor.getValue().getScriptAsString().replaceAll("\\s+", " ").trim();
        assertEquals(
                "local id = redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1]) "
                        + "redis.call('XTRIM', KEYS[1], 'MINID', ARGV[2]) "
                        + "redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[3]) "
                        + "redis.call('EXPIRE', KEYS[1], ARGV[4]) return id",
                script);
        assertEquals(List.of(payload, (now - Duration.ofHours(24).toMillis()) + "-0", "1000", "86400"),
                List.of(argumentsCaptor.getValue()));
        assertEquals(KEY, RedisKeys.getDeviceDebugLogKey(DEVICE_ID));
    }

    @Test
    void rejectsEventsThatCannotBeSerialized() {
        ObjectNode recursive = objectMapper.createObjectNode();
        recursive.set("self", recursive);
        DeviceDebugLogEvent event = new DeviceDebugLogEvent(
                null, DEVICE_ID, 100, 200, null, null, "llm", "request", "INFO", "bad",
                Map.of("value", recursive), null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> store.append(DEVICE_ID, event, 1_800_000_000_000L));

        assertTrue(exception.getMessage().contains("serialize device debug log event"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }

    @Test
    void historyUsesAFreshTwentyFourHourCutoffAndReturnsNewestEventsAscending() throws Exception {
        MapRecord<String, Object, Object> newest = record("1800000000000-2", event("payload-cursor", "newest"));
        MapRecord<String, Object, Object> older = record("1800000000000-1", event(null, "older"));
        when(streamOperations.reverseRange(eq(KEY), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(newest, older));
        long before = System.currentTimeMillis() - Duration.ofHours(24).toMillis();

        List<DeviceDebugLogEvent> history = store.history(DEVICE_ID, 2);

        long after = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        assertEquals(List.of("older", "newest"), history.stream().map(DeviceDebugLogEvent::summary).toList());
        assertEquals(List.of("1800000000000-1", "1800000000000-2"),
                history.stream().map(DeviceDebugLogEvent::cursor).toList());
        ArgumentCaptor<Range<String>> rangeCaptor = rangeCaptor();
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        verify(streamOperations).reverseRange(eq(KEY), rangeCaptor.capture(), limitCaptor.capture());
        Range<String> range = rangeCaptor.getValue();
        String cutoffId = range.getLowerBound().getValue().orElseThrow();
        long cutoff = Long.parseLong(cutoffId.substring(0, cutoffId.indexOf('-')));
        assertTrue(!range.getLowerBound().isInclusive());
        assertTrue(range.getUpperBound().isBounded());
        assertEquals("+", range.getUpperBound().getValue().orElseThrow());
        assertTrue(cutoff >= before && cutoff <= after);
        assertEquals(2, limitCaptor.getValue().getCount());
    }

    @Test
    void historyReturnsEmptyForInvalidLimitsAndMissingRecordsAndCapsTheLimit() {
        assertTrue(store.history(DEVICE_ID, 0).isEmpty());
        verify(streamOperations, never()).reverseRange(any(), any(Range.class), any(Limit.class));

        when(streamOperations.reverseRange(eq(KEY), any(Range.class), any(Limit.class))).thenReturn(null);
        assertTrue(store.history(DEVICE_ID, 5_000).isEmpty());

        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        verify(streamOperations).reverseRange(eq(KEY), any(Range.class), limitCaptor.capture());
        assertEquals(1_000, limitCaptor.getValue().getCount());
    }

    @Test
    void historyReplacesCorruptRecordsWithoutInterruptingTheBatch() throws Exception {
        MapRecord<String, Object, Object> newest = record("1800000000000-4", event(null, "after"));
        MapRecord<String, Object, Object> jsonNull = rawRecord("1800000000000-3", "null");
        MapRecord<String, Object, Object> invalidJson = rawRecord("1800000000000-2", "secret-invalid-json");
        MapRecord<String, Object, Object> missingPayload = MapRecord
                .create(KEY, Map.<Object, Object>of())
                .withId(RecordId.of("1800000000000-1"));
        MapRecord<String, Object, Object> oldest = record("1800000000000-0", event(null, "before"));
        when(streamOperations.reverseRange(eq(KEY), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(newest, jsonNull, invalidJson, missingPayload, oldest));

        List<DeviceDebugLogEvent> history = store.history(DEVICE_ID, 5);

        assertEquals(List.of(
                "1800000000000-0", "1800000000000-1", "1800000000000-2", "1800000000000-3",
                "1800000000000-4"), history.stream().map(DeviceDebugLogEvent::cursor).toList());
        assertEquals(List.of("before", "调试日志记录损坏，已跳过", "调试日志记录损坏，已跳过", "调试日志记录损坏，已跳过", "after"),
                history.stream().map(DeviceDebugLogEvent::summary).toList());
        for (DeviceDebugLogEvent event : history.subList(1, 4)) {
            assertEquals(DEVICE_ID, event.deviceId());
            assertEquals(1_800_000_000_000L, event.occurredAt());
            assertEquals(event.occurredAt(), event.receivedAt());
            assertEquals("device", event.category());
            assertEquals("debug_log.corrupted", event.eventType());
            assertEquals("warning", event.level());
            assertEquals(Map.of("reason", "corrupt_stream_record"), event.details());
            assertNull(event.durationMs());
            assertFalse(event.toString().contains("secret-invalid-json"));
        }
    }

    @Test
    void readAfterBlocksAtTheCursorAndInjectsRedisRecordIds() throws Exception {
        Duration block = Duration.ofSeconds(12);
        MapRecord<String, Object, Object> first = record("1800000000000-3", event("untrusted", "first"));
        MapRecord<String, Object, Object> second = record("1800000000000-4", event(null, "second"));
        MapRecord<String, Object, Object> corruptLast = rawRecord("1800000000000-5", "secret-broken-tail");
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset[].class)))
                .thenReturn(List.of(first, second, corruptLast));

        List<DeviceDebugLogEvent> result = store.readAfter(DEVICE_ID, "1800000000000-2", block, 20);

        assertEquals(List.of("first", "second", "调试日志记录损坏，已跳过"),
                result.stream().map(DeviceDebugLogEvent::summary).toList());
        assertEquals(List.of("1800000000000-3", "1800000000000-4", "1800000000000-5"),
                result.stream().map(DeviceDebugLogEvent::cursor).toList());
        assertFalse(result.getLast().toString().contains("secret-broken-tail"));
        ArgumentCaptor<StreamReadOptions> optionsCaptor = ArgumentCaptor.forClass(StreamReadOptions.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<StreamOffset[]> offsetsCaptor = ArgumentCaptor.forClass(StreamOffset[].class);
        verify(streamOperations).read(optionsCaptor.capture(), offsetsCaptor.capture());
        assertEquals(20L, optionsCaptor.getValue().getCount());
        assertEquals(block.toMillis(), optionsCaptor.getValue().getBlock());
        StreamOffset<?> offset = offsetsCaptor.getValue()[0];
        assertEquals(KEY, offset.getKey());
        assertEquals(ReadOffset.from("1800000000000-2"), offset.getOffset());
    }

    @Test
    void readAfterReturnsEmptyForInvalidLimitsAndMissingRecords() {
        assertTrue(store.readAfter(DEVICE_ID, "0-0", Duration.ZERO, -1).isEmpty());
        verify(streamOperations, never()).read(any(StreamReadOptions.class), any(StreamOffset[].class));

        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset[].class))).thenReturn(null);
        assertTrue(store.readAfter(DEVICE_ID, "0-0", Duration.ZERO, 2_000).isEmpty());

        ArgumentCaptor<StreamReadOptions> optionsCaptor = ArgumentCaptor.forClass(StreamReadOptions.class);
        verify(streamOperations).read(optionsCaptor.capture(), any(StreamOffset[].class));
        assertEquals(1_000L, optionsCaptor.getValue().getCount());
        assertFalse(optionsCaptor.getValue().isBlocking());
    }

    @Test
    void readAfterRejectsMissingCursorAndTreatsNonPositiveBlocksAsNonBlocking() {
        assertThrows(IllegalArgumentException.class, () -> store.readAfter(DEVICE_ID, null, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> store.readAfter(DEVICE_ID, " ", Duration.ZERO, 1));

        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset[].class))).thenReturn(List.of());
        store.readAfter(DEVICE_ID, "0-0", null, 1);
        store.readAfter(DEVICE_ID, "0-0", Duration.ofMillis(-1), 1);

        ArgumentCaptor<StreamReadOptions> optionsCaptor = ArgumentCaptor.forClass(StreamReadOptions.class);
        verify(streamOperations, org.mockito.Mockito.times(2))
                .read(optionsCaptor.capture(), any(StreamOffset[].class));
        assertTrue(optionsCaptor.getAllValues().stream().noneMatch(StreamReadOptions::isBlocking));
    }

    private MapRecord<String, Object, Object> record(String id, DeviceDebugLogEvent event) throws Exception {
        Map<Object, Object> body = new LinkedHashMap<>();
        body.put("payload", objectMapper.writeValueAsString(event));
        return MapRecord.create(KEY, body).withId(RecordId.of(id));
    }

    private MapRecord<String, Object, Object> rawRecord(String id, String payload) {
        return MapRecord.create(KEY, Map.<Object, Object>of("payload", payload)).withId(RecordId.of(id));
    }

    private DeviceDebugLogEvent event(String cursor, String summary) {
        return new DeviceDebugLogEvent(
                cursor, DEVICE_ID, 100, 200, "session", "sentence", "llm", "request", "INFO", summary,
                Map.of("model", "test"), 15L);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ArgumentCaptor<RedisScript<String>> redisScriptCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(RedisScript.class);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ArgumentCaptor<Range<String>> rangeCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Range.class);
    }
}
