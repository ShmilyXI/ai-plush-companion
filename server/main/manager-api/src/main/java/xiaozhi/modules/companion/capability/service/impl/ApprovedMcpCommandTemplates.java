package xiaozhi.modules.companion.capability.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

final class ApprovedMcpCommandTemplates {
    private static final String SAFE_ABSOLUTE_PATH = "^/(?!\\.\\.(?:/|$))(?!.*?/\\.\\.(?:/|$))[^\\r\\n]*$";
    private static final String SAFE_HTTP_URL = "^https?://[^\\s]+$";

    private ApprovedMcpCommandTemplates() {
    }

    static Map<String, Object> resolve(Map<String, Object> connection) {
        if (connection == null) return null;
        String command = text(connection.get("command"));
        List<String> args = strings(connection.get("args"));
        if (command == null || args == null) return null;

        if ("mcp-proxy".equals(command) && args.size() == 1 && args.getFirst().matches(SAFE_HTTP_URL)) {
            return template(command, List.of(), List.of(SAFE_HTTP_URL));
        }
        if ("npx".equals(command) && args.equals(List.of("-y", "@executeautomation/playwright-mcp-server"))) {
            return template(command, args, List.of());
        }
        List<String> filesystemPrefix = List.of("-y", "@modelcontextprotocol/server-filesystem");
        if ("npx".equals(command) && args.size() > filesystemPrefix.size()
                && args.subList(0, filesystemPrefix.size()).equals(filesystemPrefix)
                && args.subList(filesystemPrefix.size(), args.size()).stream()
                        .allMatch(value -> value.matches(SAFE_ABSOLUTE_PATH))) {
            return template(command, filesystemPrefix,
                    java.util.Collections.nCopies(args.size() - filesystemPrefix.size(), SAFE_ABSOLUTE_PATH));
        }
        return null;
    }

    private static Map<String, Object> template(String command, List<String> prefix, List<String> patterns) {
        return Map.of("command", command, "argsPrefix", List.copyOf(prefix),
                "extraArgPatterns", List.copyOf(patterns));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : source) {
            if (!(item instanceof String text)) return null;
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String text(Object value) {
        return value == null ? null : StringUtils.trimToNull(String.valueOf(value));
    }
}
