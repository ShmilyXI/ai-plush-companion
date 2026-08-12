package xiaozhi.modules.companion.debug.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.redis.RedisKeys;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;

@Service
public class RedisDeviceDebugLogStore implements DeviceDebugLogStore {
    private static final int MAX_EVENTS = 1_000;
    private static final long RETENTION_MILLIS = Duration.ofHours(24).toMillis();
    private static final DefaultRedisScript<String> APPEND_SCRIPT = new DefaultRedisScript<>("""
            local id = redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
            redis.call('XTRIM', KEYS[1], 'MINID', ARGV[2])
            redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return id
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisDeviceDebugLogStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String append(String deviceId, DeviceDebugLogEvent event, long now) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize device debug log event", exception);
        }

        String key = RedisKeys.getDeviceDebugLogKey(deviceId);
        String cutoffId = (now - RETENTION_MILLIS) + "-0";
        return redisTemplate.execute(APPEND_SCRIPT, List.of(key), payload, cutoffId, "1000", "86400");
    }

    @Override
    public List<DeviceDebugLogEvent> history(String deviceId, int limit) {
        int boundedLimit = boundedLimit(limit);
        if (boundedLimit == 0) {
            return List.of();
        }

        String key = RedisKeys.getDeviceDebugLogKey(deviceId);
        String cutoffId = (System.currentTimeMillis() - RETENTION_MILLIS) + "-0";
        List<MapRecord<String, Object, Object>> records = streamOperations().reverseRange(
                key,
                Range.leftOpen(cutoffId, "+"),
                Limit.limit().count(boundedLimit));
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<DeviceDebugLogEvent> events = deserialize(records);
        Collections.reverse(events);
        return events;
    }

    @Override
    public List<DeviceDebugLogEvent> readAfter(String deviceId, String cursor, Duration block, int limit) {
        int boundedLimit = boundedLimit(limit);
        if (boundedLimit == 0) {
            return List.of();
        }

        String key = RedisKeys.getDeviceDebugLogKey(deviceId);
        StreamReadOptions options = StreamReadOptions.empty().count(boundedLimit).block(block);
        List<MapRecord<String, Object, Object>> records = streamOperations().read(
                options,
                StreamOffset.create(key, ReadOffset.from(cursor)));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return deserialize(records);
    }

    private StreamOperations<String, Object, Object> streamOperations() {
        return redisTemplate.opsForStream();
    }

    private int boundedLimit(int limit) {
        if (limit <= 0) {
            return 0;
        }
        return Math.min(limit, MAX_EVENTS);
    }

    private List<DeviceDebugLogEvent> deserialize(List<MapRecord<String, Object, Object>> records) {
        List<DeviceDebugLogEvent> events = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Object payload = record.getValue().get("payload");
            try {
                DeviceDebugLogEvent event = objectMapper.readValue(String.valueOf(payload), DeviceDebugLogEvent.class);
                events.add(withCursor(event, record.getId().getValue()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Failed to deserialize device debug log event", exception);
            }
        }
        return events;
    }

    private DeviceDebugLogEvent withCursor(DeviceDebugLogEvent event, String cursor) {
        return new DeviceDebugLogEvent(
                cursor,
                event.deviceId(),
                event.occurredAt(),
                event.receivedAt(),
                event.sessionId(),
                event.sentenceId(),
                event.category(),
                event.eventType(),
                event.level(),
                event.summary(),
                event.details(),
                event.durationMs());
    }
}
