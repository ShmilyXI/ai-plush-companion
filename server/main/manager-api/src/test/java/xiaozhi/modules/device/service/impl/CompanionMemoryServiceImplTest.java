package xiaozhi.modules.device.service.impl;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dto.AgentMemoryDTO;
import xiaozhi.modules.agent.dto.AgentDTO;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.dao.CompanionMemoryMigrationDao;
import xiaozhi.modules.device.entity.CompanionMemoryMigrationEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.service.impl.CompanionMemoryServiceImpl.OperationResponse;
import xiaozhi.modules.device.service.impl.CompanionMemoryServiceImpl.MemoryListResponse;
import xiaozhi.modules.sys.service.SysParamsService;

class CompanionMemoryServiceImplTest {
    @Test
    void listMapsTrustedSourceIdsWithoutUsingCurrentDeviceProfile() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeviceEntity sourceDevice = device(7L);
        sourceDevice.setId("source-device");
        sourceDevice.setAlias("旧设备");
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(deviceService.getUserDevices(7L)).thenReturn(List.of(sourceDevice));
        AgentDTO sourceProfile = new AgentDTO();
        sourceProfile.setId("source-profile");
        sourceProfile.setAgentName("旧角色");
        when(agentService.getUserAgents(7L, null, null)).thenReturn(List.of(sourceProfile));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        var raw = new xiaozhi.modules.device.service.CompanionMemoryService.MemoryItem(
                "m1", "内容", "2026-01-01", "source-device", "source-profile", null, null);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok(new MemoryListResponse(List.of(raw))));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, agentService, restTemplate,
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class));

        var item = service.list(7L, 7L, "device-id").get(0);

        assertEquals("旧设备", item.sourceDeviceName());
        assertEquals("旧角色", item.sourceProfileName());
    }

    @Test
    void listRequiresAValidBodyButAllowsAnExplicitEmptyItemsArray() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, mock(AgentService.class), restTemplate,
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class));

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok(new MemoryListResponse(List.of())));
        assertTrue(service.list(7L, 7L, "device-id").isEmpty());

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok().build());
        assertThrows(RenException.class, () -> service.list(7L, 7L, "device-id"));

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(null);
        assertThrows(RenException.class, () -> service.list(7L, 7L, "device-id"));

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok(new MemoryListResponse(null)));
        assertThrows(RenException.class, () -> service.list(7L, 7L, "device-id"));

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenThrow(new RestClientException("malformed response"));
        RenException malformed = assertThrows(RenException.class, () -> service.list(7L, 7L, "device-id"));
        assertEquals("陪伴记忆服务暂不可用", malformed.getMsg());
    }

    @Test
    void ownerCanUpdateAndDeleteSingleMemoryThroughInternalServer() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenReturn(ResponseEntity.ok(new OperationResponse(true, null)));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, agentService, restTemplate, subscriptionService,
                mock(CompanionAuditService.class));

        service.update(7L, 7L, "device-id", "m1", "新内容");
        service.delete(7L, 7L, "device-id", "m1");

        verify(restTemplate).exchange(
                eq("http://127.0.0.1:8003/internal/companion-memory"),
                eq(HttpMethod.PUT), any(), eq(OperationResponse.class));
        verify(restTemplate).exchange(
                eq("http://127.0.0.1:8003/internal/companion-memory"),
                eq(HttpMethod.DELETE), any(), eq(OperationResponse.class));
        verify(subscriptionService, org.mockito.Mockito.times(2)).requireLongTermMemory(7L);
    }

    @Test
    void ownerCanClearMemoryThroughInternalServer() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        DeviceEntity device = device(7L);
        when(deviceService.selectById("device-id")).thenReturn(device);
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true))
                .thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(OperationResponse.class)))
                .thenReturn(ResponseEntity.ok(new OperationResponse(true, "")));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, agentService, restTemplate, subscriptionService,
                mock(CompanionAuditService.class));

        service.clear(7L, 7L, "device-id");

        verify(restTemplate).exchange(
                eq("http://127.0.0.1:8003/internal/companion-memory"),
                eq(HttpMethod.DELETE), any(), eq(OperationResponse.class));
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://127.0.0.1:8003/internal/companion-memory"),
                eq(HttpMethod.DELETE), request.capture(), eq(OperationResponse.class));
        assertEquals("Bearer secret", request.getValue().getHeaders().getFirst("Authorization"));
        assertEquals("AA:BB", ((Map<?, ?>) request.getValue().getBody()).get("mac_address"));
        verify(agentService, never()).updateAgentMemoryByDeviceMacAddress(anyString(), any(), eq(7L));
        verify(subscriptionService).requireLongTermMemory(7L);
    }

    @Test
    void falseOrEmptyOperationResponseCannotReportMutationSuccess() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, agentService, restTemplate,
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class));

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenReturn(ResponseEntity.ok(new OperationResponse(false, null)));
        RenException falseResponse = assertThrows(
                RenException.class,
                () -> service.update(7L, 7L, "device-id", "m1", "新内容"));
        RenException falseDelete = assertThrows(
                RenException.class,
                () -> service.delete(7L, 7L, "device-id", "m1"));
        RenException falseClear = assertThrows(
                RenException.class,
                () -> service.clear(7L, 7L, "device-id"));
        assertEquals("陪伴记忆服务暂不可用", falseResponse.getMsg());
        assertEquals("陪伴记忆服务暂不可用", falseDelete.getMsg());
        assertEquals("陪伴记忆服务暂不可用", falseClear.getMsg());

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenReturn(ResponseEntity.ok().build());
        RenException emptyDelete = assertThrows(
                RenException.class,
                () -> service.delete(7L, 7L, "device-id", "m1"));
        RenException emptyClear = assertThrows(
                RenException.class,
                () -> service.clear(7L, 7L, "device-id"));
        assertEquals("陪伴记忆服务暂不可用", emptyDelete.getMsg());
        assertEquals("陪伴记忆服务暂不可用", emptyClear.getMsg());

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenReturn(null);
        RenException nullResponse = assertThrows(
                RenException.class,
                () -> service.update(7L, 7L, "device-id", "m1", "新内容"));
        assertEquals("陪伴记忆服务暂不可用", nullResponse.getMsg());

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenThrow(new RestClientException("malformed response"));
        RenException malformed = assertThrows(
                RenException.class,
                () -> service.clear(7L, 7L, "device-id"));
        assertEquals("陪伴记忆服务暂不可用", malformed.getMsg());
        verify(agentService, never()).updateAgentMemoryByDeviceMacAddress(anyString(), any(), eq(7L));
    }

    @Test
    void foreignUserCannotClearMemory() {
        DeviceService deviceService = mock(DeviceService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(deviceService.selectById("device-id")).thenReturn(device(8L));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService,
                mock(SysParamsService.class),
                mock(AgentService.class),
                restTemplate,
                mock(CompanionSubscriptionService.class),
                mock(CompanionAuditService.class));

        RenException error;
        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class)) {
            messageUtils.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION))
                    .thenReturn("no permission");
            error = assertThrows(
                    RenException.class,
                    () -> service.clear(7L, 7L, "device-id"));
        }

        assertEquals(ErrorCode.NO_PERMISSION, error.getCode());
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Void.class));
    }

    @Test
    void mutationsAuditOnlyAfterProviderMutationSucceeds() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenReturn(ResponseEntity.ok(new OperationResponse(true, "已同步")));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, agentService, restTemplate,
                mock(CompanionSubscriptionService.class), auditService);

        service.update(1L, 7L, "device-id", "m1", "不能进入审计的正文");
        service.delete(1L, 7L, "device-id", "m1");
        service.clear(1L, 7L, "device-id");

        InOrder order = inOrder(restTemplate, auditService);
        order.verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), any(), eq(OperationResponse.class));
        order.verify(auditService).record(1L, 7L, "memory.update", "memory", "m1",
                Map.of("deviceId", "device-id", "itemId", "m1"));
        order.verify(restTemplate).exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(OperationResponse.class));
        order.verify(auditService).record(1L, 7L, "memory.delete", "memory", "m1",
                Map.of("deviceId", "device-id", "itemId", "m1"));
        order.verify(restTemplate).exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(OperationResponse.class));
        order.verify(auditService).record(1L, 7L, "memory.clear", "memory", "device-id",
                Map.of("deviceId", "device-id"));
    }

    @Test
    void failedProviderNeverWritesMemoryAudit() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, agentService, restTemplate,
                mock(CompanionSubscriptionService.class), auditService);

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(OperationResponse.class)))
                .thenThrow(new RestClientException("provider failed"));
        assertThrows(RenException.class,
                () -> service.update(1L, 7L, "device-id", "m1", "正文"));
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());

    }

    @Test
    void migrationRejectsDevicesFromDifferentAgentsBeforeReadingMemory() {
        DeviceService deviceService = mock(DeviceService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeviceEntity source = device(7L);
        source.setId("source");
        source.setAgentId("agent-a");
        DeviceEntity target = device(7L);
        target.setId("target");
        target.setAgentId("agent-b");
        when(deviceService.selectById("source")).thenReturn(source);
        when(deviceService.selectById("target")).thenReturn(target);
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, mock(SysParamsService.class), mock(AgentService.class), restTemplate,
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class));

        assertThrows(RuntimeException.class, () -> service.preview(7L, 7L, "source", "target"));
        verify(restTemplate, never()).exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class));
    }

    @Test
    void migrationRecordsSuccessfulImportWithoutPersistingMemoryContent() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionMemoryMigrationDao migrationDao = mock(CompanionMemoryMigrationDao.class);
        DeviceEntity source = migrationDevice("source", "agent-a");
        DeviceEntity target = migrationDevice("target", "agent-a");
        when(deviceService.selectById("source")).thenReturn(source);
        when(deviceService.selectById("target")).thenReturn(target);
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok(new MemoryListResponse(List.of())));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(CompanionMemoryServiceImpl.MigrationResponse.class)))
                .thenReturn(ResponseEntity.ok(new CompanionMemoryServiceImpl.MigrationResponse(
                        true, "merge", 0, 0, 3, 2, true)));
        CompanionAuditService audit = mock(CompanionAuditService.class);
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, mock(AgentService.class), restTemplate,
                mock(CompanionSubscriptionService.class), audit, migrationDao);

        var result = service.migrate(11L, 7L, "source", "target", "merge");

        assertEquals("SUCCEEDED", result.getOutcome());
        assertEquals(3, result.getImportedCount());
        assertEquals(2, result.getSkippedCount());
        verify(migrationDao).insert(any(CompanionMemoryMigrationEntity.class));
        verify(migrationDao).updateById(any(CompanionMemoryMigrationEntity.class));
        verify(audit).record(eq(11L), eq(7L), eq("memory.migration"), eq("memory-migration"), any(), any());
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.PUT), any(), eq(OperationResponse.class));
    }

    @Test
    void failedMigrationIsRetryableAndRetryRequiresOwner() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionMemoryMigrationDao migrationDao = mock(CompanionMemoryMigrationDao.class);
        DeviceEntity source = migrationDevice("source", "agent-a");
        DeviceEntity target = migrationDevice("target", "agent-a");
        when(deviceService.selectById("source")).thenReturn(source);
        when(deviceService.selectById("target")).thenReturn(target);
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok(new MemoryListResponse(List.of())));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(CompanionMemoryServiceImpl.MigrationResponse.class)))
                .thenThrow(new RestClientException("provider unavailable"));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, mock(AgentService.class), restTemplate,
                mock(CompanionSubscriptionService.class), mock(CompanionAuditService.class), migrationDao);

        assertThrows(RenException.class, () -> service.migrate(11L, 7L, "source", "target", "overwrite"));
        ArgumentCaptor<CompanionMemoryMigrationEntity> captor = ArgumentCaptor.forClass(CompanionMemoryMigrationEntity.class);
        verify(migrationDao).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getOutcome());
        assertTrue(captor.getValue().getRetryable());

        CompanionMemoryMigrationEntity previous = new CompanionMemoryMigrationEntity();
        previous.setId("migration-1");
        previous.setOwnerId(7L);
        previous.setSourceDeviceId("source");
        previous.setTargetDeviceId("target");
        previous.setMode("overwrite");
        previous.setRetryable(true);
        when(migrationDao.selectById("migration-1")).thenReturn(previous);
        clearInvocations(deviceService);
        assertThrows(RuntimeException.class, () -> service.retry(8L, 8L, "migration-1"));
        verify(deviceService, never()).selectById("source");
    }

    private DeviceEntity migrationDevice(String id, String agentId) {
        DeviceEntity device = device(7L);
        device.setId(id);
        device.setMacAddress(id + "-MAC");
        device.setAgentId(agentId);
        return device;
    }

    @Test
    void administratorUsesActualOwnerWhileForeignOrdinaryUserStillHasNoPermission() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        when(deviceService.selectById("device-id")).thenReturn(device(7L));
        when(deviceService.getUserDevices(7L)).thenReturn(List.of(device(7L)));
        when(sysParamsService.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(sysParamsService.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(MemoryListResponse.class)))
                .thenReturn(ResponseEntity.ok(new MemoryListResponse(List.of())));
        CompanionMemoryServiceImpl service = new CompanionMemoryServiceImpl(
                deviceService, sysParamsService, mock(AgentService.class), restTemplate,
                subscriptionService, mock(CompanionAuditService.class));

        assertTrue(service.list(1L, 7L, "device-id").isEmpty());
        verify(subscriptionService).requireLongTermMemory(7L);
        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class)) {
            messageUtils.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION))
                    .thenReturn("no permission");
            assertThrows(RenException.class, () -> service.list(8L, 8L, "device-id"));
        }
    }

    private DeviceEntity device(Long userId) {
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setUserId(userId);
        device.setMacAddress("AA:BB");
        return device;
    }
}
