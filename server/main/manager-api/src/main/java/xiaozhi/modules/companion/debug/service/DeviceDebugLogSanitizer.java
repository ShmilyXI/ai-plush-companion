package xiaozhi.modules.companion.debug.service;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public class DeviceDebugLogSanitizer {
    public static final String TRUNCATION_MARKER = "…[已截断]";

    private static final int SUMMARY_LIMIT = 1000;
    private static final int DETAILS_STRING_LIMIT = 4000;
    private static final int CONTAINER_LIMIT = 50;
    private static final int MAX_DEPTH = 4;
    private static final int NODE_LIMIT = 200;
    private static final int OUTPUT_UTF8_LIMIT = 64 * 1024;
    private static final int KEY_CODE_POINT_LIMIT = 128;
    private static final Object OMITTED = new Object();
    private static final ObjectMapper JSON_MAPPER = createJsonMapper();

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "key",
            "token",
            "secret",
            "password",
            "authorization",
            "credential",
            "system_prompt",
            "raw_audio",
            "audio_base64",
            "messages");
    private static final List<String> SENSITIVE_SUFFIXES = List.of(
            "_key",
            "_token",
            "_secret",
            "_password",
            "_authorization",
            "_credential");
    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern PUNCTUATION = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern REPEATED_UNDERSCORE = Pattern.compile("_+");
    private static final Pattern EDGE_UNDERSCORE = Pattern.compile("^_|_$");

    public String sanitizeSummary(String source) {
        return truncate(source == null ? "" : source, SUMMARY_LIMIT);
    }

    public Map<String, Object> sanitizeDetails(Map<?, ?> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        return sanitizeMap(source, 0, new Budget());
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> source, int depth, Budget budget) {
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
        if (!budget.reserveBytes(2)) {
            return sanitized;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (sanitized.size() >= CONTAINER_LIMIT) {
                break;
            }
            if (!budget.consumeNode()) {
                addMapTruncationMarker(sanitized, budget);
                break;
            }
            String key = String.valueOf(entry.getKey());
            if (exceedsCodePointLimit(key, KEY_CODE_POINT_LIMIT) || isSensitiveKey(key)) {
                continue;
            }
            int checkpoint = budget.checkpoint();
            int entryPrefixBytes = serializedBytes(key) + 1 + (sanitized.isEmpty() ? 0 : 1);
            if (!budget.reserveBytes(entryPrefixBytes)) {
                addMapTruncationMarker(sanitized, budget);
                break;
            }
            Object value = sanitizeValue(entry.getValue(), depth + 1, budget);
            if (value == OMITTED) {
                budget.restoreBytes(checkpoint);
                addMapTruncationMarker(sanitized, budget);
                break;
            }
            sanitized.put(key, value);
            if (budget.isExhausted()) {
                break;
            }
        }
        return sanitized;
    }

    private Object sanitizeValue(Object value, int depth, Budget budget) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            if (!couldFitScalar(value, budget.remainingBytes())) {
                budget.exhaust();
                return OMITTED;
            }
            return budget.reserveBytes(serializedBytes(value)) ? value : OMITTED;
        }
        if (value instanceof String string) {
            return truncateToJsonBudget(string, DETAILS_STRING_LIMIT, budget);
        }
        if (depth > MAX_DEPTH && (value instanceof Map<?, ?> || value instanceof Collection<?> || value.getClass().isArray())) {
            return budget.reserveBytes(serializedBytes(TRUNCATION_MARKER)) ? TRUNCATION_MARKER : OMITTED;
        }
        if ((value instanceof Map<?, ?> || value instanceof Collection<?> || value.getClass().isArray())
                && budget.remainingBytes() < 2) {
            budget.exhaust();
            return OMITTED;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map, depth, budget);
        }
        if (value instanceof Collection<?> collection) {
            return sanitizeCollection(collection, depth, budget);
        }
        if (value.getClass().isArray()) {
            return sanitizeArray(value, depth, budget);
        }
        return truncateToJsonBudget(String.valueOf(value), DETAILS_STRING_LIMIT, budget);
    }

    private List<Object> sanitizeCollection(Collection<?> source, int depth, Budget budget) {
        List<Object> sanitized = new ArrayList<>(Math.min(source.size(), CONTAINER_LIMIT));
        if (!budget.reserveBytes(2)) {
            return sanitized;
        }
        for (Object value : source) {
            if (sanitized.size() >= CONTAINER_LIMIT) {
                break;
            }
            if (!budget.consumeNode()) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            int checkpoint = budget.checkpoint();
            if (!sanitized.isEmpty() && !budget.reserveBytes(1)) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            Object cleaned = sanitizeValue(value, depth + 1, budget);
            if (cleaned == OMITTED) {
                budget.restoreBytes(checkpoint);
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            sanitized.add(cleaned);
            if (budget.isExhausted()) {
                break;
            }
        }
        return sanitized;
    }

    private List<Object> sanitizeArray(Object source, int depth, Budget budget) {
        int length = Math.min(Array.getLength(source), CONTAINER_LIMIT);
        List<Object> sanitized = new ArrayList<>(length);
        if (!budget.reserveBytes(2)) {
            return sanitized;
        }
        for (int index = 0; index < length; index++) {
            if (!budget.consumeNode()) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            int checkpoint = budget.checkpoint();
            if (!sanitized.isEmpty() && !budget.reserveBytes(1)) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            Object cleaned = sanitizeValue(Array.get(source, index), depth + 1, budget);
            if (cleaned == OMITTED) {
                budget.restoreBytes(checkpoint);
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            sanitized.add(cleaned);
            if (budget.isExhausted()) {
                break;
            }
        }
        return sanitized;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = normalizeKey(key);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return SENSITIVE_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }

    private boolean exceedsCodePointLimit(String value, int limit) {
        int index = 0;
        for (int count = 0; count <= limit && index < value.length(); count++) {
            index = value.offsetByCodePoints(index, 1);
            if (count == limit) {
                return true;
            }
        }
        return false;
    }

    private String normalizeKey(String key) {
        String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC);
        String acronymSplit = ACRONYM_BOUNDARY.matcher(normalized).replaceAll("$1_$2");
        String camelSplit = CAMEL_BOUNDARY.matcher(acronymSplit).replaceAll("$1_$2");
        String punctuationReplaced = PUNCTUATION.matcher(camelSplit).replaceAll("_");
        String collapsed = REPEATED_UNDERSCORE.matcher(punctuationReplaced).replaceAll("_");
        return EDGE_UNDERSCORE.matcher(collapsed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private String truncate(String source, int limit) {
        if (source.length() <= limit) {
            return source;
        }
        int end = safeEnd(source, limit - TRUNCATION_MARKER.length());
        return source.substring(0, end) + TRUNCATION_MARKER;
    }

    private Object truncateToJsonBudget(String source, int limit, Budget budget) {
        String limited = truncate(source, limit);
        int limitedBytes = serializedBytes(limited);
        if (limitedBytes <= budget.remainingBytes()) {
            budget.reserveBytes(limitedBytes);
            return limited;
        }

        int markerBytes = serializedBytes(TRUNCATION_MARKER);
        if (markerBytes > budget.remainingBytes()) {
            budget.exhaust();
            return OMITTED;
        }

        int high = safeEnd(source, Math.min(source.length(), limit - TRUNCATION_MARKER.length()));
        int low = 0;
        String best = TRUNCATION_MARKER;
        while (low <= high) {
            int probe = low + (high - low) / 2;
            int end = safeEnd(source, probe);
            String candidate = source.substring(0, end) + TRUNCATION_MARKER;
            if (serializedBytes(candidate) <= budget.remainingBytes()) {
                best = candidate;
                low = probe + 1;
            } else {
                high = probe - 1;
            }
        }
        budget.reserveBytes(serializedBytes(best));
        budget.exhaust();
        return best;
    }

    private int safeEnd(String source, int requestedEnd) {
        int end = Math.min(requestedEnd, source.length());
        if (end > 0 && end < source.length()
                && Character.isHighSurrogate(source.charAt(end - 1))
                && Character.isLowSurrogate(source.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    private void addMapTruncationMarker(Map<String, Object> target, Budget budget) {
        if (target.size() >= CONTAINER_LIMIT || target.containsKey(TRUNCATION_MARKER)) {
            return;
        }
        int requiredBytes = serializedBytes(TRUNCATION_MARKER) * 2 + 1 + (target.isEmpty() ? 0 : 1);
        if (budget.reserveMarkerBytes(requiredBytes)) {
            target.put(TRUNCATION_MARKER, TRUNCATION_MARKER);
        }
    }

    private void addCollectionTruncationMarker(List<Object> target, Budget budget) {
        int requiredBytes = serializedBytes(TRUNCATION_MARKER) + (target.isEmpty() ? 0 : 1);
        if (target.size() < CONTAINER_LIMIT && budget.reserveMarkerBytes(requiredBytes)) {
            target.add(TRUNCATION_MARKER);
        }
    }

    private boolean couldFitScalar(Object value, int remainingBytes) {
        if (value instanceof BigInteger integer) {
            return minimumDecimalDigits(integer.bitLength()) + 1 <= remainingBytes;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.precision() + 16 <= remainingBytes;
        }
        return true;
    }

    private int minimumDecimalDigits(int bitLength) {
        if (bitLength <= 1) {
            return 1;
        }
        return (int) Math.floor((bitLength - 1) * Math.log10(2)) + 1;
    }

    private int serializedBytes(Object value) {
        try {
            return JSON_MAPPER.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法计算调试事件 JSON 大小", exception);
        }
    }

    private static ObjectMapper createJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(module);
        return mapper;
    }

    private final class Budget {
        private int remainingNodes = NODE_LIMIT;
        private int remainingBytes = OUTPUT_UTF8_LIMIT;
        private boolean exhausted;

        private boolean consumeNode() {
            if (exhausted || remainingNodes == 0) {
                exhausted = true;
                return false;
            }
            remainingNodes--;
            return true;
        }

        private boolean reserveBytes(int bytes) {
            if (exhausted || bytes > remainingBytes) {
                exhausted = true;
                return false;
            }
            remainingBytes -= bytes;
            return true;
        }

        private boolean reserveMarkerBytes(int bytes) {
            if (bytes > remainingBytes) {
                return false;
            }
            remainingBytes -= bytes;
            return true;
        }

        private int remainingBytes() {
            return remainingBytes;
        }

        private int checkpoint() {
            return remainingBytes;
        }

        private void restoreBytes(int checkpoint) {
            remainingBytes = checkpoint;
        }

        private boolean isExhausted() {
            return exhausted;
        }

        private void exhaust() {
            exhausted = true;
        }
    }
}
