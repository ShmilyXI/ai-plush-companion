package xiaozhi.modules.companion.capability.service;

import java.util.List;
import java.util.Map;

public interface CapabilityRuntimeClient {
    List<PluginExecutor> pluginExecutors();

    McpTestResult testMcp(McpTestRequest request);

    record PluginExecutor(String name, String description, Map<String, Object> inputSchema) {
    }

    record McpTestRequest(String transport, Map<String, Object> connectionConfig,
            Map<String, Object> approvedCommandTemplate) {
    }

    record McpTool(String name, Map<String, Object> inputSchema) {
    }

    record McpTestResult(boolean success, String errorClass, List<McpTool> tools) {
    }
}
