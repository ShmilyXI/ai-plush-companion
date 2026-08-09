package xiaozhi.modules.device.service.impl;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dto.AgentMemoryDTO;
import xiaozhi.modules.agent.dto.AgentDTO;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.CompanionMemoryService;
import xiaozhi.modules.device.service.CompanionMemoryService.MemoryItem;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class CompanionMemoryServiceImpl implements CompanionMemoryService {
    private final DeviceService deviceService;
    private final SysParamsService sysParamsService;
    private final AgentService agentService;
    private final RestTemplate restTemplate;
    private final CompanionSubscriptionService subscriptionService;
    private final CompanionAuditService auditService;

    @Autowired
    public CompanionMemoryServiceImpl(DeviceService deviceService, SysParamsService sysParamsService,
            AgentService agentService, RestTemplate restTemplate,
            CompanionSubscriptionService subscriptionService, CompanionAuditService auditService) {
        this.deviceService = deviceService;
        this.sysParamsService = sysParamsService;
        this.agentService = agentService;
        this.restTemplate = restTemplate;
        this.subscriptionService = subscriptionService;
        this.auditService = auditService;
    }

    @Override
    public List<MemoryItem> list(Long operatorId, Long ownerId, String deviceId) {
        DeviceEntity device = requireOwnerAndEntitlement(ownerId, deviceId);
        RequestContext context = requestContext();
        URI uri = UriComponentsBuilder.fromUriString(context.serverHttp())
                .path("/internal/companion-memory")
                .queryParam("mac_address", device.getMacAddress())
                .queryParam("client_id", "manager-api")
                .build().encode().toUri();
        try {
            ResponseEntity<MemoryListResponse> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(headers(context.secret())),
                    MemoryListResponse.class);
            MemoryListResponse body = response == null ? null : response.getBody();
            if (body == null || body.items() == null) {
                throw unavailable();
            }
            if (body.items().isEmpty()) {
                return List.of();
            }
            Map<String, String> deviceNames = deviceService.getUserDevices(ownerId).stream()
                    .collect(Collectors.toMap(DeviceEntity::getId,
                            item -> StringUtils.defaultIfBlank(item.getAlias(), item.getMacAddress()),
                            (left, right) -> left));
            Map<String, String> profileNames = agentService.getUserAgents(ownerId, null, null).stream()
                    .collect(Collectors.toMap(AgentDTO::getId, AgentDTO::getAgentName, (left, right) -> left));
            return body.items().stream().map(item -> new MemoryItem(
                    item.id(), item.content(), item.updatedAt(), item.sourceDeviceId(), item.sourceProfileId(),
                    deviceNames.get(item.sourceDeviceId()), profileNames.get(item.sourceProfileId()))).toList();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public void update(Long operatorId, Long ownerId, String deviceId, String memoryId, String content) {
        DeviceEntity device = requireOwnerAndEntitlement(ownerId, deviceId);
        syncLocalSummary(ownerId, device, exchangeOperation(HttpMethod.PUT, requestBody(device, memoryId, content)));
        auditService.record(operatorId, ownerId, "memory.update", "memory", memoryId,
                Map.of("deviceId", deviceId, "itemId", memoryId));
    }

    @Override
    public void delete(Long operatorId, Long ownerId, String deviceId, String memoryId) {
        DeviceEntity device = requireOwnerAndEntitlement(ownerId, deviceId);
        syncLocalSummary(ownerId, device, exchangeOperation(HttpMethod.DELETE, requestBody(device, memoryId, null)));
        auditService.record(operatorId, ownerId, "memory.delete", "memory", memoryId,
                Map.of("deviceId", deviceId, "itemId", memoryId));
    }

    @Override
    public void clear(Long operatorId, Long ownerId, String deviceId) {
        DeviceEntity device = requireOwnerAndEntitlement(ownerId, deviceId);
        exchangeOperation(HttpMethod.DELETE, requestBody(device, null, null));

        AgentMemoryDTO memory = new AgentMemoryDTO();
        memory.setSummaryMemory("");
        agentService.updateAgentMemoryByDeviceMacAddress(
                device.getMacAddress(), memory, ownerId);
        auditService.record(operatorId, ownerId, "memory.clear", "memory", deviceId,
                Map.of("deviceId", deviceId));
    }

    private DeviceEntity requireOwnerAndEntitlement(Long ownerId, String deviceId) {
        DeviceEntity device = deviceService.selectById(deviceId);
        if (device == null || !Objects.equals(device.getUserId(), ownerId)) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        subscriptionService.requireLongTermMemory(ownerId);
        return device;
    }

    private OperationResponse exchangeOperation(HttpMethod method, Map<String, Object> body) {
        RequestContext context = requestContext();
        try {
            ResponseEntity<OperationResponse> response = restTemplate.exchange(
                    context.serverHttp() + "/internal/companion-memory",
                    method,
                    new HttpEntity<>(body, headers(context.secret())),
                    OperationResponse.class);
            OperationResponse operation = response == null ? null : response.getBody();
            if (operation == null || !operation.success()) {
                throw unavailable();
            }
            return operation;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private void syncLocalSummary(Long userId, DeviceEntity device, OperationResponse response) {
        if (response == null || response.summary() == null) {
            return;
        }
        AgentMemoryDTO memory = new AgentMemoryDTO();
        memory.setSummaryMemory(response.summary());
        agentService.updateAgentMemoryByDeviceMacAddress(device.getMacAddress(), memory, userId);
    }

    private Map<String, Object> requestBody(DeviceEntity device, String memoryId, String content) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("mac_address", device.getMacAddress());
        body.put("client_id", "manager-api");
        if (memoryId != null) {
            body.put("memory_id", memoryId);
        }
        if (content != null) {
            body.put("content", content);
        }
        return body;
    }

    private RequestContext requestContext() {
        String serverHttp = sysParamsService.getValue(Constant.SERVER_HTTP, true);
        String secret = sysParamsService.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isBlank(serverHttp) || StringUtils.isBlank(secret)) {
            throw new IllegalStateException("companion memory service is not configured");
        }
        return new RequestContext(serverHttp, secret);
    }

    private HttpHeaders headers(String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private RenException unavailable() {
        return new RenException("陪伴记忆服务暂不可用");
    }

    private record RequestContext(String serverHttp, String secret) {
    }

    record MemoryListResponse(List<MemoryItem> items) {
    }

    record OperationResponse(boolean success, String summary) {
    }
}
