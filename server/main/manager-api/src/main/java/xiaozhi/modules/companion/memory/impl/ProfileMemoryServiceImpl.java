package xiaozhi.modules.companion.memory.impl;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Autowired;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.memory.ProfileMemoryNamespace;
import xiaozhi.modules.companion.memory.ProfileMemoryService;

@Service
public class ProfileMemoryServiceImpl implements ProfileMemoryService {
    private final AgentDao agentDao;
    private final RestTemplate restTemplate;
    private String serverUrl;
    private String serverSecret;

    @Autowired
    public ProfileMemoryServiceImpl(AgentDao agentDao, RestTemplate restTemplate,
            @Value("${companion.profile-memory.server-url:${server.http:}}") String serverUrl,
            @Value("${companion.profile-memory.secret:${server.secret:}}") String serverSecret) {
        this.agentDao = agentDao;
        this.restTemplate = restTemplate;
        this.serverUrl = serverUrl;
        this.serverSecret = serverSecret;
    }

    @Override
    public MemoryView list(Long userId, String profileId) {
        AgentEntity profile = requireProfile(userId, profileId);
        Map<String, Object> request = requestData(userId, profile, null, null);
        try {
            ResponseEntity<MemoryResponse> response = restTemplate.exchange(
                    endpoint(), HttpMethod.POST, new HttpEntity<>(request, headers()), MemoryResponse.class);
            MemoryResponse body = response == null ? null : response.getBody();
            if (response == null || !response.getStatusCode().is2xxSuccessful()
                    || body == null || !body.success() || body.items() == null) {
                throw new RenException("陪伴记忆服务暂不可用");
            }
            return new MemoryView(runtimeEnabled(profile), body.items());
        } catch (RestClientException exception) {
            throw new RenException("陪伴记忆服务暂不可用");
        }
    }

    @Override
    public void update(Long userId, String profileId, String memoryId, String content) {
        AgentEntity profile = requireProfile(userId, profileId);
        if (StringUtils.isBlank(memoryId) || StringUtils.isBlank(content)) throw new RenException("记忆内容不能为空");
        operation("update", requestData(userId, profile, memoryId, content));
    }

    @Override
    public void delete(Long userId, String profileId, String memoryId) {
        AgentEntity profile = requireProfile(userId, profileId);
        if (StringUtils.isBlank(memoryId)) throw new RenException("记忆标识不能为空");
        operation("delete", requestData(userId, profile, memoryId, null));
    }

    @Override
    public void clear(Long userId, String profileId) {
        AgentEntity profile = requireProfile(userId, profileId);
        operation("clear", requestData(userId, profile, null, null));
    }

    private void operation(String operation, Map<String, Object> body) {
        body.put("operation", operation);
        try {
            ResponseEntity<MemoryResponse> response = restTemplate.exchange(
                    endpoint(), HttpMethod.POST, new HttpEntity<>(body, headers()), MemoryResponse.class);
            MemoryResponse result = response == null ? null : response.getBody();
            if (response == null || !response.getStatusCode().is2xxSuccessful()
                    || result == null || !result.success()) {
                throw new RenException("记忆操作失败");
            }
        } catch (RestClientException exception) {
            throw new RenException("陪伴记忆服务暂不可用");
        }
    }

    private AgentEntity requireProfile(Long userId, String profileId) {
        AgentEntity profile = agentDao.selectById(profileId);
        if (profile == null || userId == null || !userId.equals(profile.getUserId())
                || !Integer.valueOf(1).equals(profile.getCompanionEnabled())) {
            throw new RenException("角色不存在");
        }
        if (profile.getConsumerDeletedAt() != null) throw new RenException("profile_deleted");
        return profile;
    }

    private boolean runtimeEnabled(AgentEntity profile) {
        return profile.getMemoryEnabled() == null || profile.getMemoryEnabled() == 1;
    }

    private Map<String, Object> requestData(Long userId, AgentEntity profile, String memoryId, String content) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("user_id", userId);
        body.put("profile_id", profile.getId());
        body.put("memory_namespace", ProfileMemoryNamespace.of(userId, profile.getId()));
        body.put("memory_enabled", runtimeEnabled(profile));
        if (memoryId != null) body.put("memory_id", memoryId);
        if (content != null) body.put("content", content);
        return body;
    }

    private String endpoint() {
        if (StringUtils.isBlank(serverUrl)) throw new RenException("陪伴记忆服务暂未配置");
        return serverUrl.replaceAll("/+$", "") + "/internal/companion-profile-memory";
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.isNotBlank(serverSecret)) headers.setBearerAuth(serverSecret);
        return headers;
    }

    public record MemoryResponse(List<MemoryItem> items, boolean success,
            @JsonProperty("memory_enabled") boolean memoryEnabled) {
    }
}
