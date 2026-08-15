package xiaozhi.modules.model.tencentdb;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;

@Service
@RequiredArgsConstructor
public class OpenAiEmbeddingConnectionTester {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final TencentDbMemoryUpstreamClient upstreamClient;

    public CompanionModelTestVO test(JSONObject config) {
        long started = System.nanoTime();
        try {
            URI target = embeddingsUri(required(config, "base_url"));
            String apiKey = required(config, "api_key");
            String modelName = required(config, "model_name");
            Integer dimensions = config == null ? null : config.getInt("dimensions");
            if (dimensions == null || dimensions <= 0) {
                return failure(started, "Embedding 模型配置不完整");
            }

            JSONObject payload = new JSONObject()
                    .set("model", modelName)
                    .set("input", "连接测试");
            if (config.getBool("send_dimensions", true)) {
                payload.set("dimensions", dimensions);
            }

            HttpResponse<InputStream> response = upstreamClient.post(
                    target, apiKey, payload.toString().getBytes(StandardCharsets.UTF_8), TIMEOUT);
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return failure(started, "Embedding 连接失败: HTTP " + response.statusCode());
                }
                JSONObject result = JSONUtil.parseObj(new String(body.readAllBytes(), StandardCharsets.UTF_8));
                JSONArray data = result.getJSONArray("data");
                if (data == null || data.isEmpty()
                        || data.getJSONObject(0).getJSONArray("embedding") == null
                        || data.getJSONObject(0).getJSONArray("embedding").isEmpty()) {
                    return failure(started, "Embedding 连接失败: 返回数据无向量");
                }
                return new CompanionModelTestVO(true, elapsed(started), "Embedding 连接成功");
            }
        } catch (IllegalArgumentException exception) {
            return failure(started, "Embedding 模型配置不完整");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure(started, "Embedding 连接失败: InterruptedException");
        } catch (Exception exception) {
            return failure(started, "Embedding 连接失败: " + exception.getClass().getSimpleName());
        }
    }

    private String required(JSONObject config, String key) {
        String value = config == null ? null : config.getStr(key);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException();
        }
        return value.trim();
    }

    private URI embeddingsUri(String baseUrl) {
        URI root = URI.create(baseUrl);
        if (!root.isAbsolute() || root.getHost() == null
                || !("http".equalsIgnoreCase(root.getScheme()) || "https".equalsIgnoreCase(root.getScheme()))) {
            throw new IllegalArgumentException();
        }
        String value = root.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value + "/embeddings");
    }

    private CompanionModelTestVO failure(long started, String message) {
        return new CompanionModelTestVO(false, elapsed(started), message);
    }

    private int elapsed(long started) {
        return Math.toIntExact(Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
