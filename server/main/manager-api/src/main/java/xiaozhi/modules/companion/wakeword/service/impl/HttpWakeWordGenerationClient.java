package xiaozhi.modules.companion.wakeword.service.impl;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.crypto.digest.DigestUtil;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import xiaozhi.modules.companion.wakeword.service.WakeWordGenerationClient;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class HttpWakeWordGenerationClient implements WakeWordGenerationClient {
    private static final int MAX_CONTENT_SIZE = 0x300000 - 4096;
    private final RestTemplate restTemplate;
    private final SysParamsService params;

    public HttpWakeWordGenerationClient(RestTemplate restTemplate, SysParamsService params) {
        this.restTemplate = restTemplate;
        this.params = params;
    }

    @Override
    public GeneratedAsset generate(DeviceWakeWordEntity row) {
        String serverHttp = params.getValue(Constant.SERVER_HTTP, true);
        String secret = params.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isBlank(serverHttp) || StringUtils.isBlank(secret)) {
            throw new RenException("唤醒词生成服务未配置");
        }
        String baseUrl = serverHttp.endsWith("/")
                ? serverHttp.substring(0, serverHttp.length() - 1)
                : serverHttp;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> request = Map.of(
                "device_id", row.getDeviceId(),
                "word", row.getDesiredWord(),
                "version", row.getDesiredVersion(),
                "chip", row.getChipModel(),
                "slot_size", row.getSlotSize());
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    baseUrl + "/internal/wake-word-assets",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    byte[].class);
            byte[] content = response.getBody();
            String expectedSha256 = response.getHeaders().getFirst("X-Wake-Word-Sha256");
            long expectedSize = parseLong(response.getHeaders().getFirst("X-Wake-Word-Size"));
            long expectedVersion = parseLong(response.getHeaders().getFirst("X-Wake-Word-Version"));
            if (!response.getStatusCode().is2xxSuccessful() || content == null || content.length > MAX_CONTENT_SIZE
                    || expectedSize != content.length || expectedVersion != row.getDesiredVersion()) {
                throw new RenException("唤醒词生成响应无效");
            }
            String actualSha256 = DigestUtil.sha256Hex(content);
            if (expectedSha256 == null || !expectedSha256.equals(actualSha256)) {
                throw new RenException("唤醒词生成响应哈希不匹配");
            }
            return new GeneratedAsset(content, actualSha256, content.length);
        } catch (RestClientException exception) {
            throw new RenException("唤醒词生成服务不可用", exception);
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new RenException("唤醒词生成响应无效", exception);
        }
    }
}
