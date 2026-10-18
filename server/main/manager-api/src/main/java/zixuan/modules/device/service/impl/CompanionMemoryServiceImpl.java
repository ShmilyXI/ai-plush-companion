package zixuan.modules.device.service.impl;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
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

import zixuan.common.constant.Constant;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.modules.agent.dto.AgentDTO;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.dao.CompanionMemoryMigrationDao;
import zixuan.modules.device.entity.CompanionMemoryMigrationEntity;
import zixuan.modules.device.service.CompanionMemoryService;
import zixuan.modules.device.service.CompanionMemoryService.MemoryItem;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.device.vo.CompanionMemoryMigrationVO;
import zixuan.modules.sys.service.SysParamsService;

@Service
public class CompanionMemoryServiceImpl implements CompanionMemoryService {
    private static final ConcurrentHashMap<String, Object> MIGRATION_LOCKS = new ConcurrentHashMap<>();
    private final DeviceService deviceService;
    private final SysParamsService sysParamsService;
    private final AgentService agentService;
    private final RestTemplate restTemplate;
    private final CompanionSubscriptionService subscriptionService;
    private final CompanionAuditService auditService;
    private final CompanionMemoryMigrationDao migrationDao;

    @Autowired
    public CompanionMemoryServiceImpl(DeviceService deviceService, SysParamsService sysParamsService,
            AgentService agentService, RestTemplate restTemplate,
            CompanionSubscriptionService subscriptionService, CompanionAuditService auditService) {
        this(deviceService, sysParamsService, agentService, restTemplate, subscriptionService, auditService, null);
    }

    public CompanionMemoryServiceImpl(DeviceService deviceService, SysParamsService sysParamsService,
            AgentService agentService, RestTemplate restTemplate,
            CompanionSubscriptionService subscriptionService, CompanionAuditService auditService,
            CompanionMemoryMigrationDao migrationDao) {
        this.deviceService = deviceService;
        this.sysParamsService = sysParamsService;
        this.agentService = agentService;
        this.restTemplate = restTemplate;
        this.subscriptionService = subscriptionService;
        this.auditService = auditService;
        this.migrationDao = migrationDao;
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
        exchangeOperation(HttpMethod.PUT, requestBody(device, memoryId, content));
        auditService.record(operatorId, ownerId, "memory.update", "memory", memoryId,
                Map.of("deviceId", deviceId, "itemId", memoryId));
    }

    @Override
    public void delete(Long operatorId, Long ownerId, String deviceId, String memoryId) {
        DeviceEntity device = requireOwnerAndEntitlement(ownerId, deviceId);
        exchangeOperation(HttpMethod.DELETE, requestBody(device, memoryId, null));
        auditService.record(operatorId, ownerId, "memory.delete", "memory", memoryId,
                Map.of("deviceId", deviceId, "itemId", memoryId));
    }

    @Override
    public void clear(Long operatorId, Long ownerId, String deviceId) {
        DeviceEntity device = requireOwnerAndEntitlement(ownerId, deviceId);
        exchangeOperation(HttpMethod.DELETE, requestBody(device, null, null));

        auditService.record(operatorId, ownerId, "memory.clear", "memory", deviceId,
                Map.of("deviceId", deviceId));
    }

    @Override
    public MigrationPreview preview(Long operatorId, Long ownerId, String sourceDeviceId, String targetDeviceId) {
        Pair devices = requireMigrationDevices(ownerId, sourceDeviceId, targetDeviceId);
        return new MigrationPreview(devices.source().getAgentId(), sourceDeviceId, targetDeviceId,
                list(operatorId, ownerId, sourceDeviceId).size(),
                list(operatorId, ownerId, targetDeviceId).size(), "merge");
    }

    @Override
    public CompanionMemoryMigrationVO migrate(Long operatorId, Long ownerId, String sourceDeviceId,
            String targetDeviceId, String mode) {
        String normalizedMode = StringUtils.defaultString(mode).toLowerCase();
        String lockKey = ownerId + ":" + sourceDeviceId + ":" + targetDeviceId + ":" + normalizedMode;
        Object lock = MIGRATION_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            return migrateLocked(operatorId, ownerId, sourceDeviceId, targetDeviceId, normalizedMode);
        }
    }

    private CompanionMemoryMigrationVO migrateLocked(Long operatorId, Long ownerId, String sourceDeviceId,
            String targetDeviceId, String mode) {
        if (!"merge".equalsIgnoreCase(mode) && !"overwrite".equalsIgnoreCase(mode)) {
            throw new RenException("迁移模式必须是 merge 或 overwrite");
        }
        Pair devices = requireMigrationDevices(ownerId, sourceDeviceId, targetDeviceId);
        List<MemoryItem> source = list(operatorId, ownerId, sourceDeviceId);
        List<MemoryItem> target = list(operatorId, ownerId, targetDeviceId);
        CompanionMemoryMigrationEntity audit = new CompanionMemoryMigrationEntity();
        audit.setOwnerId(ownerId);
        audit.setAgentId(devices.source().getAgentId());
        audit.setSourceDeviceId(sourceDeviceId);
        audit.setTargetDeviceId(targetDeviceId);
        audit.setMode(mode.toLowerCase());
        audit.setSourceCount(source.size());
        audit.setTargetCount(target.size());
        audit.setImportedCount(0);
        audit.setSkippedCount(0);
        audit.setOutcome("RUNNING");
        audit.setRetryable(false);
        audit.setRecovered(true);
        audit.setOperatorId(operatorId);
        audit.setCreatedAt(new java.util.Date());
        audit.setUpdatedAt(audit.getCreatedAt());
        if (migrationDao != null) migrationDao.insert(audit);
        try {
            RequestContext context = requestContext();
            ResponseEntity<MigrationResponse> response = restTemplate.exchange(
                    context.serverHttp() + "/internal/companion-memory/migration",
                    HttpMethod.POST,
                    new HttpEntity<>(migrationBody(devices.source(), devices.target(), mode), headers(context.secret())),
                    MigrationResponse.class);
            MigrationResponse result = response == null ? null : response.getBody();
            if (result == null || !result.success()) throw unavailable();
            audit.setImportedCount(result.importedCount());
            audit.setSkippedCount(result.skippedCount());
            audit.setRecovered(result.recovered());
            audit.setRetryable(false);
            audit.setOutcome("SUCCEEDED");
        } catch (RestClientException | RenException exception) {
            audit.setOutcome("FAILED");
            audit.setRetryable(true);
            if (migrationDao != null) migrationDao.updateById(audit);
            if (exception instanceof RenException ren) throw ren;
            throw unavailable();
        }
        if (migrationDao != null) migrationDao.updateById(audit);
        auditService.record(operatorId, ownerId, "memory.migration", "memory-migration", audit.getId(),
                Map.of("mode", audit.getMode(), "sourceCount", audit.getSourceCount(),
                        "targetCount", audit.getTargetCount(), "importedCount", audit.getImportedCount(),
                        "skippedCount", audit.getSkippedCount(), "outcome", audit.getOutcome()));
        return CompanionMemoryMigrationVO.from(audit);
    }

    @Override
    public List<CompanionMemoryMigrationVO> history(Long ownerId, int limit) {
        if (migrationDao == null) return List.of();
        return migrationDao.selectRecentByOwner(ownerId, Math.min(Math.max(limit, 1), 100)).stream()
                .map(CompanionMemoryMigrationVO::from).toList();
    }

    @Override
    public CompanionMemoryMigrationVO retry(Long operatorId, Long ownerId, String migrationId) {
        if (migrationDao == null) throw unavailable();
        CompanionMemoryMigrationEntity previous = migrationDao.selectById(migrationId);
        if (previous == null || !Objects.equals(previous.getOwnerId(), ownerId)
                || !Boolean.TRUE.equals(previous.getRetryable())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return migrate(operatorId, ownerId, previous.getSourceDeviceId(), previous.getTargetDeviceId(), previous.getMode());
    }

    private Pair requireMigrationDevices(Long ownerId, String sourceId, String targetId) {
        DeviceEntity source = requireOwnerAndEntitlement(ownerId, sourceId);
        DeviceEntity target = requireOwnerAndEntitlement(ownerId, targetId);
        if (Objects.equals(source.getId(), target.getId()) || StringUtils.isBlank(source.getAgentId())
                || !Objects.equals(source.getAgentId(), target.getAgentId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return new Pair(source, target);
    }

    private Map<String, Object> migrationBody(DeviceEntity source, DeviceEntity target, String mode) {
        return Map.of("source_mac_address", source.getMacAddress(), "target_mac_address", target.getMacAddress(),
                "mode", mode.toLowerCase(), "client_id", "manager-api");
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

    record MigrationResponse(boolean success, String mode,
            @JsonProperty("source_count") int sourceCount,
            @JsonProperty("target_count") int targetCount,
            @JsonProperty("imported_count") int importedCount,
            @JsonProperty("skipped_count") int skippedCount,
            boolean recovered) {
    }

    private record Pair(DeviceEntity source, DeviceEntity target) {
    }
}
