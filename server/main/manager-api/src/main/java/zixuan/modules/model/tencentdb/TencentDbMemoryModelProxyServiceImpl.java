package zixuan.modules.model.tencentdb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TencentDbMemoryModelProxyServiceImpl implements TencentDbMemoryModelProxyService {
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final List<String> CHAT_FIELDS = List.of(
            "messages", "temperature", "max_tokens", "tools", "tool_choice", "response_format", "stream",
            "stop", "seed", "top_p", "frequency_penalty", "presence_penalty");
    private static final List<String> EMBEDDING_FIELDS = List.of("input", "encoding_format", "user");

    private final TencentDbMemoryModelSettingsService settingsService;
    private final TencentDbMemoryUpstreamClient upstreamClient;

    @Override
    public TencentDbMemoryProxyResponse chat(JSONObject request) {
        return chat(request, settingsService.requireEnabled());
    }

    @Override
    public TencentDbMemoryProxyResponse embeddings(JSONObject request) {
        return embeddings(request, settingsService.requireEnabled());
    }

    TencentDbMemoryProxyResponse chat(JSONObject request, TencentDbMemoryModelSettings settings) {
        JSONObject shaped = shape(request, CHAT_FIELDS);
        shaped.set("model", settings.llmModel());
        return forward("chat", settings.llmBaseUrl(), "/chat/completions", settings.llmApiKey(),
                settings.llmModel(), shaped);
    }

    TencentDbMemoryProxyResponse embeddings(JSONObject request, TencentDbMemoryModelSettings settings) {
        if (request == null || !request.containsKey("input") || request.get("input") == null) {
            throw new IllegalArgumentException("Embedding input 不能为空");
        }
        JSONObject shaped = shape(request, EMBEDDING_FIELDS);
        shaped.set("model", settings.embeddingModel());
        if (settings.embeddingSendDimensions()) {
            shaped.set("dimensions", settings.embeddingDimensions());
        }
        return forward("embedding", settings.embeddingBaseUrl(), "/embeddings", settings.embeddingApiKey(),
                settings.embeddingModel(), shaped);
    }

    private JSONObject shape(JSONObject request, List<String> allowed) {
        JSONObject shaped = new JSONObject();
        if (request == null) {
            return shaped;
        }
        for (String field : allowed) {
            if (request.containsKey(field)) {
                shaped.set(field, request.get(field));
            }
        }
        return shaped;
    }

    private TencentDbMemoryProxyResponse forward(String kind, URI baseUrl, String suffix, String apiKey,
            String model, JSONObject payload) {
        long started = System.nanoTime();
        URI target = target(baseUrl, suffix);
        try {
            HttpResponse<java.io.InputStream> upstream = upstreamClient.post(
                    target, apiKey, payload.toString().getBytes(StandardCharsets.UTF_8), TIMEOUT);
            int status = upstream.statusCode();
            String contentType = upstream.headers().firstValue("content-type").orElse("application/json");
            String requestId = upstream.headers().firstValue("x-request-id").orElse(null);
            log.info("TencentDB memory model proxy kind={} host={} model={} elapsedMs={} status={} contentType={} requestId={}",
                    kind, target.getHost(), model, elapsed(started), status, contentType, requestId);
            if (status < 200 || status >= 300) {
                upstream.body().close();
                byte[] error = JSONUtil.createObj()
                        .set("error", "上游模型服务返回 HTTP " + status)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
                return new TencentDbMemoryProxyResponse(
                        502, "application/json", Map.of(), new ByteArrayInputStream(error));
            }
            return new TencentDbMemoryProxyResponse(status, contentType, responseHeaders(upstream), upstream.body());
        } catch (IOException exception) {
            throw new IllegalStateException("上游模型服务连接失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("上游模型服务连接中断", exception);
        }
    }

    private Map<String, String> responseHeaders(HttpResponse<?> upstream) {
        Map<String, String> result = new LinkedHashMap<>();
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if ((lower.equals("x-request-id") || lower.startsWith("openai-")) && !values.isEmpty()) {
                result.put(name, values.get(0));
            }
        });
        return result;
    }

    private URI target(URI baseUrl, String suffix) {
        String value = baseUrl.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value + suffix);
    }

    private long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
