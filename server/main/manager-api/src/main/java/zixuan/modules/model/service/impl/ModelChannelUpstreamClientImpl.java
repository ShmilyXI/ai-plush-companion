package zixuan.modules.model.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import zixuan.common.exception.RenException;
import zixuan.modules.model.service.ModelChannelUpstreamClient;

@Service
public class ModelChannelUpstreamClientImpl implements ModelChannelUpstreamClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MODELS = 1000;
    private static final int MAX_MODEL_ID_LENGTH = 200;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Override
    public List<String> listModels(String providerCode, String baseUrl, String apiKey) {
        boolean ollama = "ollama".equalsIgnoreCase(providerCode);
        HttpRequest request = request(ollama, baseUrl, apiKey);
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new RenException("连接超时，请检查接口地址");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RenException("连接被中断，请重试");
        } catch (IOException exception) {
            throw new RenException("连接失败，请检查接口地址");
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new RenException("密钥无效或没有权限（HTTP " + status + "）");
            }
            if (status == 404) {
                throw new RenException("这个地址不支持拉取模型列表（HTTP 404），可以手动添加模型");
            }
            if (status < 200 || status >= 300) {
                throw new RenException("服务返回 HTTP " + status);
            }
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new RenException("模型列表过大，请手动添加模型");
            }
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new RenException("读取模型列表失败，请重试");
        }
    }

    private HttpRequest request(boolean ollama, String baseUrl, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target(ollama, baseUrl))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (!ollama && StringUtils.isNotBlank(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            return builder.build();
        } catch (IllegalArgumentException exception) {
            throw new RenException("接口地址或密钥格式无效");
        }
    }

    private URI target(boolean ollama, String baseUrl) {
        String normalized = StringUtils.trimToEmpty(baseUrl);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new RenException("缺少接口地址");
        }
        if (ollama) {
            // Ollama 的原生接口不在 /v1 下。
            if (normalized.endsWith("/v1")) {
                normalized = normalized.substring(0, normalized.length() - "/v1".length());
            }
            return URI.create(normalized + "/api/tags");
        }
        return URI.create(normalized.endsWith("/models") ? normalized : normalized + "/models");
    }

    private List<String> parse(String body) {
        JSON json;
        try {
            json = JSONUtil.parse(body);
        } catch (RuntimeException exception) {
            throw new RenException("服务返回的模型列表格式无法识别");
        }
        JSONArray items = null;
        if (json instanceof JSONArray array) {
            items = array;
        } else if (json instanceof JSONObject object) {
            items = object.getJSONArray("data");
            if (items == null) {
                items = object.getJSONArray("models");
            }
        }
        if (items == null) {
            throw new RenException("服务返回的模型列表格式无法识别");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : items) {
            String id = modelId(item);
            if (id != null && id.length() <= MAX_MODEL_ID_LENGTH) {
                ids.add(id);
            }
            if (ids.size() >= MAX_MODELS) {
                break;
            }
        }
        List<String> result = new ArrayList<>(ids);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private String modelId(Object item) {
        if (item instanceof CharSequence text) {
            return StringUtils.trimToNull(text.toString());
        }
        if (item instanceof JSONObject object) {
            for (String key : List.of("id", "name", "model")) {
                String value = StringUtils.trimToNull(object.getStr(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }
}
