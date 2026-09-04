package zixuan.modules.companion.model.service.impl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import zixuan.modules.companion.model.service.CompanionModelConnectionTester;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;

@Service
public class CompanionModelConnectionTesterImpl implements CompanionModelConnectionTester {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Override
    public CompanionModelTestVO test(String providerCode, Map<String, Object> runtimeConfig) {
        long started = System.nanoTime();
        if (!"openai".equalsIgnoreCase(providerCode)) {
            return new CompanionModelTestVO(false, elapsed(started), "当前供应器暂不支持自动测试");
        }
        String baseUrl = firstString(runtimeConfig, "base_url", "api_url", "url");
        if (StringUtils.isBlank(baseUrl)) {
            return new CompanionModelTestVO(false, elapsed(started), "缺少 API 地址");
        }
        try {
            String normalized = baseUrl.trim();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            String target = normalized.endsWith("/models") ? normalized : normalized + "/models";
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target)).timeout(TIMEOUT).GET();
            String credential = firstString(runtimeConfig, "api_key", "api_password", "access_token", "token");
            if (StringUtils.isNotBlank(credential)) {
                request.header("Authorization", "Bearer " + credential);
            }
            int status = client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean success = status >= 200 && status < 300;
            return new CompanionModelTestVO(success, elapsed(started),
                    success ? "连接成功" : "服务返回 HTTP " + status);
        } catch (Exception exception) {
            return new CompanionModelTestVO(false, elapsed(started),
                    "连接失败: " + exception.getClass().getSimpleName());
        }
    }

    private String firstString(Map<String, Object> config, String... keys) {
        if (config == null) return null;
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) return value.toString();
        }
        return null;
    }

    private int elapsed(long started) {
        return Math.toIntExact(Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
