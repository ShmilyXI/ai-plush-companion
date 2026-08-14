package xiaozhi.modules.model.tencentdb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;

@Service
public class TencentDbMemoryConnectionTesterImpl implements TencentDbMemoryConnectionTester {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP\\s+(\\d{3})");

    private final TencentDbMemoryModelProxyServiceImpl proxyService;
    private final HttpClient httpClient;

    public TencentDbMemoryConnectionTesterImpl(TencentDbMemoryModelProxyServiceImpl proxyService) {
        this(proxyService, HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    TencentDbMemoryConnectionTesterImpl(TencentDbMemoryModelProxyServiceImpl proxyService, HttpClient httpClient) {
        this.proxyService = proxyService;
        this.httpClient = httpClient;
    }

    @Override
    public CompanionModelTestVO test(TencentDbMemoryRuntimeSettings runtime) {
        long started = System.nanoTime();
        String coreFailure = testCore(runtime);
        if (coreFailure != null) return failure(started, "MemoryCore 连接失败: " + coreFailure);

        String llmFailure = testLlm(runtime.modelSettings());
        if (llmFailure != null) return failure(started, "记忆 LLM 连接失败: " + llmFailure);

        String embeddingFailure = testEmbedding(runtime.modelSettings());
        if (embeddingFailure != null) return failure(started, "Embedding 连接失败: " + embeddingFailure);

        return new CompanionModelTestVO(
                true, elapsed(started), "MemoryCore 可用，记忆 LLM 可用，Embedding 可用");
    }

    private String testCore(TencentDbMemoryRuntimeSettings runtime) {
        try {
            URI target = append(runtime.memoryCoreUrl(), "/health");
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + runtime.memoryCoreApiKey())
                    .GET()
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300 ? null : "HTTP " + status;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return exception.getClass().getSimpleName();
        }
    }

    private String testLlm(TencentDbMemoryModelSettings settings) {
        JSONObject request = JSONUtil.createObj()
                .set("messages", JSONUtil.createArray().set(JSONUtil.createObj()
                        .set("role", "user")
                        .set("content", "连接测试")))
                .set("stream", false)
                .set("max_tokens", 8);
        try (TencentDbMemoryProxyResponse response = proxyService.chat(request, settings)) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            return response.status() >= 200 && response.status() < 300 ? null : statusMessage(response.status(), body);
        } catch (IOException | RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private String testEmbedding(TencentDbMemoryModelSettings settings) {
        JSONObject request = JSONUtil.createObj().set("input", JSONUtil.createArray().set("连接测试"));
        try (TencentDbMemoryProxyResponse response = proxyService.embeddings(request, settings)) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            if (response.status() < 200 || response.status() >= 300) {
                return statusMessage(response.status(), body);
            }
            JSONObject payload = JSONUtil.parseObj(body);
            int actual = payload.getJSONArray("data").getJSONObject(0).getJSONArray("embedding").size();
            if (actual != settings.embeddingDimensions()) {
                return "向量维度不符，期望 " + settings.embeddingDimensions() + "，实际 " + actual;
            }
            return null;
        } catch (IOException | RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private String statusMessage(int proxyStatus, String body) {
        Matcher matcher = HTTP_STATUS.matcher(body == null ? "" : body);
        return matcher.find() ? "HTTP " + matcher.group(1) : "HTTP " + proxyStatus;
    }

    private URI append(URI root, String suffix) {
        String value = root.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value + suffix);
    }

    private CompanionModelTestVO failure(long started, String message) {
        return new CompanionModelTestVO(false, elapsed(started), message);
    }

    private int elapsed(long started) {
        return Math.toIntExact(Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
