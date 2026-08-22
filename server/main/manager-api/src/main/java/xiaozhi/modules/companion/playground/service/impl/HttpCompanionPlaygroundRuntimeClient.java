package xiaozhi.modules.companion.playground.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

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
        exchange("/xiaozhi/internal/playground", HttpMethod.POST, body);
    }

    @Override
    public void input(String sessionId, PlaygroundInputDTO input) {
        if (!enabled()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("sequence", 1);
        body.put("kind", input.getKind().name().toLowerCase());
        body.put("text", input.getText());
        body.put("audio_ref", input.getAudioRef());
        body.put("image_ref", input.getImageRef());
        body.put("activity", input.getActivity());
        exchange("/xiaozhi/internal/playground/" + sessionId + "/inputs", HttpMethod.POST, body);
    }

    @Override
    public void close(String sessionId) {
        if (!enabled()) return;
        exchange("/xiaozhi/internal/playground/" + sessionId, HttpMethod.DELETE, null);
    }

    private boolean enabled() {
        return StringUtils.isNotBlank(params.getValue(Constant.SERVER_HTTP, true));
    }

    private void exchange(String path, HttpMethod method, Object body) {
        try {
            restTemplate.exchange(endpoint(path), method, new HttpEntity<>(body, headers()), String.class);
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
