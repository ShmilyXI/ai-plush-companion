package xiaozhi.modules.companion.playground.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.playground.dto.PlaygroundInputDTO;
import xiaozhi.modules.companion.playground.service.CompanionPlaygroundRuntimeClient;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class HttpCompanionPlaygroundRuntimeClient implements CompanionPlaygroundRuntimeClient {
    private final RestTemplate restTemplate;
    private final SysParamsService params;

    public HttpCompanionPlaygroundRuntimeClient(RestTemplate restTemplate, SysParamsService params) {
        this.restTemplate = restTemplate;
        this.params = params;
    }

    @Override
    public void create(String sessionId, long snapshotVersion, Map<String, Object> config) {
        if (!enabled()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("snapshot_version", snapshotVersion);
        body.put("config", config);
        body.put("virtual_device", config.get("virtualDevice"));
        body.put("runtime_models", config.get("runtimeModels"));
        exchange("/xiaozhi/internal/playground", HttpMethod.POST, body);
    }

    @Override
    public List<Map<String, Object>> input(String sessionId, long sequence, PlaygroundInputDTO input) {
        if (!enabled()) return List.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("sequence", sequence);
        body.put("kind", input.getKind().name().toLowerCase());
        body.put("text", input.getText());
        body.put("audio_ref", input.getAudioRef());
        body.put("image_ref", input.getImageRef());
        body.put("activity", input.getActivity());
        String response = exchange("/xiaozhi/internal/playground/" + sessionId + "/inputs", HttpMethod.POST, body);
        Map<String, Object> parsed = JsonUtils.parseMap(response);
        Object events = parsed == null ? null : parsed.get("events");
        if (!(events instanceof List<?> values)) return List.of();
        return values.stream().filter(item -> item instanceof Map<?, ?>).map(item -> (Map<String, Object>) item).toList();
    }

    @Override
    public void close(String sessionId) {
        if (!enabled()) return;
        exchange("/xiaozhi/internal/playground/" + sessionId, HttpMethod.DELETE, null);
    }

    private boolean enabled() {
        return StringUtils.isNotBlank(params.getValue(Constant.SERVER_HTTP, true));
    }

    private String exchange(String path, HttpMethod method, Object body) {
        try {
            return restTemplate.exchange(endpoint(path), method, new HttpEntity<>(body, headers()), String.class).getBody();
        } catch (RestClientException exception) {
            throw new RenException("虚拟运行时暂时不可用", exception);
        }
    }

    private String endpoint(String path) {
        String base = params.getValue(Constant.SERVER_HTTP, true).trim();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String secret = params.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isNotBlank(secret)) headers.setBearerAuth(secret);
        return headers;
    }
}
