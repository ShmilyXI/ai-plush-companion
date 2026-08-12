package xiaozhi.modules.companion.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import xiaozhi.modules.companion.debug.dto.DeviceDebugLogIngestDTO;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogDraft;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;
import xiaozhi.modules.companion.debug.service.DeviceDebugLogSanitizer;
import xiaozhi.modules.companion.debug.vo.DeviceDebugLogHistoryVO;

class DeviceDebugLogSanitizerTest {
    private final DeviceDebugLogSanitizer sanitizer = new DeviceDebugLogSanitizer();

    @Test
    void removesSensitiveAndForbiddenFieldsRecursively() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("Authorization", "Bearer secret");
        nested.put("safe", "visible");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("model", "qwen");
        details.put("apiKey", "secret");
        details.put("nested", nested);
        details.put("systemPrompt", "hidden prompt");
        details.put("rawAudio", "hidden audio");

        Map<String, Object> sanitized = sanitizer.sanitizeDetails(details);

        assertEquals(Map.of("model", "qwen", "nested", Map.of("safe", "visible")), sanitized);
        assertFalse(sanitized.toString().contains("secret"));
        assertFalse(sanitized.toString().contains("hidden"));
    }

    @Test
    void normalizesKeysOnlyForSensitivityChecks() {
        Map<Object, Object> details = new LinkedHashMap<>();
        details.put("monkeyCount", 7);
        details.put("access-token", "secret");
        details.put("clientSecret", "secret");
        details.put("credential", "secret");
        details.put(123, "safe numeric key");

        Map<String, Object> sanitized = sanitizer.sanitizeDetails(details);

        assertEquals(7, sanitized.get("monkeyCount"));
        assertEquals("safe numeric key", sanitized.get("123"));
        assertEquals(2, sanitized.size());
    }

    @Test
    void truncatesSummaryAndDetailStringsWithExplicitMarker() {
        String longText = "x".repeat(5000);

        String summary = sanitizer.sanitizeSummary(longText);
        String detail = (String) sanitizer.sanitizeDetails(Map.of("value", longText)).get("value");

        assertEquals(1000, summary.length());
        assertEquals(4000, detail.length());
        assertTrue(summary.endsWith(DeviceDebugLogSanitizer.TRUNCATION_MARKER));
        assertTrue(detail.endsWith(DeviceDebugLogSanitizer.TRUNCATION_MARKER));
        assertEquals("", sanitizer.sanitizeSummary(null));
        assertTrue(sanitizer.sanitizeDetails(null).isEmpty());
    }

    @Test
    void limitsMapsCollectionsAndArraysWhilePreservingOrder() {
        Map<String, Object> oversizedMap = new LinkedHashMap<>();
        List<Integer> oversizedList = new ArrayList<>();
        int[] oversizedArray = new int[55];
        for (int index = 0; index < 55; index++) {
            oversizedMap.put("field" + index, index);
            oversizedList.add(index);
            oversizedArray[index] = index;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("map", oversizedMap);
        details.put("list", oversizedList);
        details.put("array", oversizedArray);

        Map<String, Object> sanitized = sanitizer.sanitizeDetails(details);

        Map<?, ?> sanitizedMap = assertInstanceOf(LinkedHashMap.class, sanitized.get("map"));
        List<?> sanitizedList = assertInstanceOf(List.class, sanitized.get("list"));
        List<?> sanitizedArray = assertInstanceOf(List.class, sanitized.get("array"));
        assertEquals(50, sanitizedMap.size());
        assertEquals(List.of("field0", "field1"), sanitizedMap.keySet().stream().limit(2).toList());
        assertEquals(50, sanitizedList.size());
        assertEquals(50, sanitizedArray.size());
        assertEquals(49, sanitizedArray.get(49));
    }

    @Test
    void limitsNestingAndStringifiesUnsupportedObjects() {
        Map<String, Object> levelFive = Map.of("value", "too deep");
        Map<String, Object> levelFour = Map.of("levelFive", levelFive);
        Map<String, Object> levelThree = Map.of("levelFour", levelFour);
        Map<String, Object> levelTwo = Map.of("levelThree", levelThree);
        Map<String, Object> levelOne = Map.of("levelTwo", levelTwo);
        Object unsupported = new Object() {
            @Override
            public String toString() {
                return "z".repeat(5000);
            }
        };

        Map<String, Object> sanitized = sanitizer.sanitizeDetails(Map.of(
                "nested", levelOne,
                "unsupported", unsupported));

        Map<?, ?> nested = assertInstanceOf(Map.class, sanitized.get("nested"));
        Map<?, ?> second = assertInstanceOf(Map.class, nested.get("levelTwo"));
        Map<?, ?> third = assertInstanceOf(Map.class, second.get("levelThree"));
        Map<?, ?> fourth = assertInstanceOf(Map.class, third.get("levelFour"));
        assertEquals(DeviceDebugLogSanitizer.TRUNCATION_MARKER, fourth.get("levelFive"));

        String stringified = assertInstanceOf(String.class, sanitized.get("unsupported"));
        assertEquals(4000, stringified.length());
        assertTrue(stringified.endsWith(DeviceDebugLogSanitizer.TRUNCATION_MARKER));
    }

    @Test
    void ingestDtoValidatesItsContractAndMapsToDraft() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        DeviceDebugLogIngestDTO dto = new DeviceDebugLogIngestDTO();
        dto.setDeviceRef("device-a");
        dto.setSessionId("session-a");
        dto.setSentenceId("sentence-a");
        dto.setCategory("model_tool");
        dto.setEventType("tool.completed");
        dto.setLevel("info");
        dto.setSummary("completed");
        dto.setDetails(Map.of("tool", "weather"));
        dto.setOccurredAt(123L);
        dto.setDurationMs(45L);

        assertTrue(validator.validate(dto).isEmpty());
        assertEquals(new DeviceDebugLogDraft(
                "session-a", "sentence-a", "model_tool", "tool.completed", "info",
                "completed", Map.of("tool", "weather"), 123L, 45L), dto.toDraft());

        dto.setCategory("invalid");
        dto.setEventType("invalid");
        dto.setLevel("fatal");
        dto.setDurationMs(-1L);
        assertEquals(4, validator.validate(dto).size());
    }

    @Test
    void recordsExposeStableComponentOrder() {
        assertEquals(List.of(
                "sessionId", "sentenceId", "category", "eventType", "level",
                "summary", "details", "occurredAt", "durationMs"), componentNames(DeviceDebugLogDraft.class));
        assertEquals(List.of(
                "cursor", "deviceId", "occurredAt", "receivedAt", "sessionId", "sentenceId",
                "category", "eventType", "level", "summary", "details", "durationMs"),
                componentNames(DeviceDebugLogEvent.class));
        assertEquals(List.of("events", "lastCursor"), componentNames(DeviceDebugLogHistoryVO.class));
    }

    private List<String> componentNames(Class<?> recordType) {
        return List.of(recordType.getRecordComponents()).stream().map(RecordComponent::getName).toList();
    }
}
