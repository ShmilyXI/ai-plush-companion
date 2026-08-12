package xiaozhi.modules.companion.debug.service;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
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
    private static final int NODE_LIMIT = 200;
    private static final int OUTPUT_UTF8_LIMIT = 64 * 1024;
    private static final int KEY_CODE_POINT_LIMIT = 128;
    private static final Object OMITTED = new Object();

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
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (sanitized.size() >= CONTAINER_LIMIT || !budget.consumeNode()) {
                addMapTruncationMarker(sanitized, budget);
                break;
            }
            String key = String.valueOf(entry.getKey());
            if (exceedsCodePointLimit(key, KEY_CODE_POINT_LIMIT) || isSensitiveKey(key)) {
                continue;
            }
            if (!budget.reserve(key)) {
                addMapTruncationMarker(sanitized, budget);
                break;
            }
            Object value = sanitizeValue(entry.getValue(), depth + 1, budget);
            if (value == OMITTED) {
                addMapTruncationMarker(sanitized, budget);
                break;
            }
            sanitized.put(key, value);
        }
        return sanitized;
    }

    private Object sanitizeValue(Object value, int depth, Budget budget) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof String string) {
            return truncate(string, DETAILS_STRING_LIMIT, budget);
        }
        if (depth > MAX_DEPTH && (value instanceof Map<?, ?> || value instanceof Collection<?> || value.getClass().isArray())) {
            return budget.reserve(TRUNCATION_MARKER) ? TRUNCATION_MARKER : OMITTED;
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
        return truncate(String.valueOf(value), DETAILS_STRING_LIMIT, budget);
    }

    private List<Object> sanitizeCollection(Collection<?> source, int depth, Budget budget) {
        List<Object> sanitized = new ArrayList<>(Math.min(source.size(), CONTAINER_LIMIT));
        for (Object value : source) {
            if (sanitized.size() >= CONTAINER_LIMIT || !budget.consumeNode()) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            Object cleaned = sanitizeValue(value, depth + 1, budget);
            if (cleaned == OMITTED) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            sanitized.add(cleaned);
        }
        return sanitized;
    }

    private List<Object> sanitizeArray(Object source, int depth, Budget budget) {
        int length = Math.min(Array.getLength(source), CONTAINER_LIMIT);
        List<Object> sanitized = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            if (!budget.consumeNode()) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            Object cleaned = sanitizeValue(Array.get(source, index), depth + 1, budget);
            if (cleaned == OMITTED) {
                addCollectionTruncationMarker(sanitized, budget);
                break;
            }
            sanitized.add(cleaned);
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

    private Object truncate(String source, int limit, Budget budget) {
        int sourceBytes = utf8Length(source);
        if (source.length() <= limit && sourceBytes <= budget.remainingBytes()) {
            budget.reserveBytes(sourceBytes);
            return source;
        }

        int markerBytes = utf8Length(TRUNCATION_MARKER);
        if (budget.remainingBytes() < markerBytes) {
            budget.exhaustBytes();
            return OMITTED;
        }

        int prefixUnitLimit = Math.max(0, limit - TRUNCATION_MARKER.length());
        int prefixByteLimit = budget.remainingBytes() - markerBytes;
        int end = 0;
        int usedBytes = 0;
        while (end < source.length() && end < prefixUnitLimit) {
            int codePoint = source.codePointAt(end);
            int charCount = Character.charCount(codePoint);
            int codePointBytes = utf8Length(new String(Character.toChars(codePoint)));
            if (end + charCount > prefixUnitLimit || usedBytes + codePointBytes > prefixByteLimit) {
                break;
            }
            end += charCount;
            usedBytes += codePointBytes;
        }
        budget.reserveBytes(usedBytes + markerBytes);
        budget.exhaustBytesIfNeeded();
        return source.substring(0, end) + TRUNCATION_MARKER;
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
        int requiredBytes = utf8Length(TRUNCATION_MARKER) * 2;
        if (budget.reserveMarkerBytes(requiredBytes)) {
            target.put(TRUNCATION_MARKER, TRUNCATION_MARKER);
        }
    }

    private void addCollectionTruncationMarker(List<Object> target, Budget budget) {
        if (target.size() < CONTAINER_LIMIT && budget.reserveMarkerBytes(utf8Length(TRUNCATION_MARKER))) {
            target.add(TRUNCATION_MARKER);
        }
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
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

        private boolean reserve(String value) {
            return reserveBytes(utf8Length(value));
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

        private void exhaustBytes() {
            exhausted = true;
            remainingBytes = 0;
        }

        private void exhaustBytesIfNeeded() {
            if (remainingBytes < utf8Length(TRUNCATION_MARKER)) {
                exhausted = true;
                remainingBytes = 0;
            }
        }
    }
}
