package xiaozhi.modules.companion.capability.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.service.CapabilityRuntimeClient;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class HttpCapabilityRuntimeClient implements CapabilityRuntimeClient {
    private final RestTemplate restTemplate;
    private final SysParamsService params;

    public HttpCapabilityRuntimeClient(RestTemplate restTemplate, SysParamsService params) {
        this.restTemplate = restTemplate;
        this.params = params;
    }

    @Override
    public List<PluginExecutor> pluginExecutors() {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint("/internal/capabilities/plugin-executors"), HttpMethod.GET,
                    new HttpEntity<>(headers()), String.class);
            Map<String, Object> body = responseBody(response);
            Object raw = body.get("executors");
            if (!(raw instanceof List<?> values)) throw new RenException("Plugin 执行器目录响应无效");
            List<PluginExecutor> result = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> item)) throw new RenException("Plugin 执行器目录响应无效");
                String name = text(item.get("name"));
                String description = item.get("description") instanceof String string ? string : "";
                Map<String, Object> schema = objectMap(item.get("inputSchema"));
                if (name == null || schema == null) throw new RenException("Plugin 执行器目录响应无效");
                result.add(new PluginExecutor(name, description, Map.copyOf(schema)));
            }
            return List.copyOf(result);
        } catch (RestClientException exception) {
            throw new RenException("Plugin 执行器目录暂时不可用", exception);
        }
    }

    @Override
    public McpTestResult testMcp(McpTestRequest request) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint("/internal/capabilities/mcp-test"), HttpMethod.POST,
                    new HttpEntity<>(request, jsonHeaders()), String.class);
            return parseMcpResult(responseBody(response));
        } catch (HttpStatusCodeException exception) {
            try {
                return parseMcpResult(JsonUtils.parseMap(exception.getResponseBodyAsString()));
            } catch (RuntimeException ignored) {
                return new McpTestResult(false, "RuntimeHttpError", List.of());
            }
        } catch (RestClientException exception) {
            return new McpTestResult(false, "RuntimeUnavailable", List.of());
        }
    }

    private McpTestResult parseMcpResult(Map<String, Object> body) {
        if (!(body.get("success") instanceof Boolean success)) {
            throw new RenException("MCP 连接测试响应无效");
        }
        String errorClass = text(body.get("errorClass"));
        Object rawTools = body.getOrDefault("tools", List.of());
        if (!(rawTools instanceof List<?> values)) throw new RenException("MCP 连接测试响应无效");
        List<McpTool> tools = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) throw new RenException("MCP 连接测试响应无效");
            String name = text(item.get("name"));
            Map<String, Object> schema = objectMap(item.get("inputSchema"));
            if (name == null || schema == null) throw new RenException("MCP 连接测试响应无效");
            tools.add(new McpTool(name, Map.copyOf(schema)));
        }
        return new McpTestResult(success, errorClass, List.copyOf(tools));
    }

    private Map<String, Object> responseBody(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful() || StringUtils.isBlank(response.getBody())) {
            throw new RenException("运行时能力服务响应无效");
        }
        Map<String, Object> body = JsonUtils.parseMap(response.getBody());
        if (body == null) throw new RenException("运行时能力服务响应无效");
        return body;
    }

    private String endpoint(String path) {
        String serverHttp = params.getValue(Constant.SERVER_HTTP, true);
        if (StringUtils.isBlank(serverHttp)) throw new RenException("运行时 HTTP 服务未配置");
        String base = serverHttp.trim();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }

    private HttpHeaders headers() {
        String secret = params.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isBlank(secret)) throw new RenException("server.secret 未配置");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secret);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String text(Object value) {
        return value instanceof String string ? StringUtils.trimToNull(string) : null;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) return null;
            result.put(key, entry.getValue());
        }
        return result;
    }
}
