package xiaozhi.modules.companion.debug.service;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class DeviceDebugLogSanitizer {
    public static final String TRUNCATION_MARKER = "…[已截断]";

    private static final int SUMMARY_LIMIT = 1000;
    private static final int DETAILS_STRING_LIMIT = 4000;
    private static final int CONTAINER_LIMIT = 50;
    private static final int MAX_DEPTH = 4;

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
        return sanitizeMap(source, 0);
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> source, int depth) {
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (sanitized.size() >= CONTAINER_LIMIT) {
                break;
            }
            String key = String.valueOf(entry.getKey());
            if (!isSensitiveKey(key)) {
                sanitized.put(key, sanitizeValue(entry.getValue(), depth + 1));
            }
        }
        return sanitized;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof String string) {
            return truncate(string, DETAILS_STRING_LIMIT);
        }
        if (depth > MAX_DEPTH && (value instanceof Map<?, ?> || value instanceof Collection<?> || value.getClass().isArray())) {
            return TRUNCATION_MARKER;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map, depth);
        }
        if (value instanceof Collection<?> collection) {
            return sanitizeCollection(collection, depth);
        }
        if (value.getClass().isArray()) {
            return sanitizeArray(value, depth);
        }
        return truncate(String.valueOf(value), DETAILS_STRING_LIMIT);
    }

    private List<Object> sanitizeCollection(Collection<?> source, int depth) {
        List<Object> sanitized = new ArrayList<>(Math.min(source.size(), CONTAINER_LIMIT));
        for (Object value : source) {
            if (sanitized.size() >= CONTAINER_LIMIT) {
                break;
            }
            sanitized.add(sanitizeValue(value, depth + 1));
        }
        return sanitized;
    }

    private List<Object> sanitizeArray(Object source, int depth) {
        int length = Math.min(Array.getLength(source), CONTAINER_LIMIT);
        List<Object> sanitized = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            sanitized.add(sanitizeValue(Array.get(source, index), depth + 1));
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

    private String normalizeKey(String key) {
        String acronymSplit = ACRONYM_BOUNDARY.matcher(key).replaceAll("$1_$2");
        String camelSplit = CAMEL_BOUNDARY.matcher(acronymSplit).replaceAll("$1_$2");
        String punctuationReplaced = PUNCTUATION.matcher(camelSplit).replaceAll("_");
        String collapsed = REPEATED_UNDERSCORE.matcher(punctuationReplaced).replaceAll("_");
        return EDGE_UNDERSCORE.matcher(collapsed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private String truncate(String source, int limit) {
        if (source.length() <= limit) {
            return source;
        }
        return source.substring(0, limit - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }
}
