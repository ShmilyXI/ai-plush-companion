package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.Map;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogDraft;
import xiaozhi.modules.companion.debug.service.DeviceDebugLogService;
import xiaozhi.modules.companion.dto.CompanionDeviceBindDTO;
import xiaozhi.modules.companion.dto.CompanionDeviceCommandDTO;
import xiaozhi.modules.companion.service.impl.CompanionDeviceServiceImpl;
import xiaozhi.modules.companion.vo.CompanionProfileVO;
import xiaozhi.modules.companion.vo.CompanionDeviceVO;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.dto.DeviceUpdateDTO;

class CompanionDeviceServiceImplTest {

    @Test
    void bindingIsSingleTransactionEntry() throws Exception {
        assertNotNull(CompanionDeviceServiceImpl.class
                .getMethod("bind", Long.class, CompanionDeviceBindDTO.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void bindingCreatesDefaultProfileWhenUserHasNone() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        when(profileService.resolveForDeviceBinding(7L, null, "template-xiaozhi", "小智"))
                .thenReturn("profile-xiaozhi");
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, profileService, mock(DeviceDebugLogService.class));

        service.bind(7L, new CompanionDeviceBindDTO("123456", null));

        InOrder order = inOrder(profileService, deviceService);
        order.verify(profileService).resolveForDeviceBinding(7L, null, "template-xiaozhi", "小智");
        order.verify(deviceService).deviceActivationWithLockedUser(7L, "profile-xiaozhi", "123456");
    }

    @Test
    void bindingUsesRequestedOwnedProfile() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        when(profileService.resolveForDeviceBinding(7L, "profile-a", "template-xiaozhi", "小智"))
                .thenReturn("profile-a");
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, profileService, mock(DeviceDebugLogService.class));

        service.bind(7L, new CompanionDeviceBindDTO("123456", "profile-a"));

        verify(deviceService).deviceActivationWithLockedUser(7L, "profile-a", "123456");
    }

    @Test
    void switchRejectsForeignProfile() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            RenException foreignProfile = new RenException(ErrorCode.NO_PERMISSION);
            when(profileService.get(7L, "profile-owned-by-8"))
                    .thenThrow(foreignProfile);
            CompanionDeviceService service = new CompanionDeviceServiceImpl(
                    deviceService, profileService, mock(DeviceDebugLogService.class));

            assertThrows(RenException.class,
                    () -> service.switchProfile(7L, "device-a", "profile-owned-by-8"));
        }

        verify(deviceService, never()).switchCompanionProfile(7L, "device-a", "profile-owned-by-8");
    }

    @Test
    void volumeCommandUsesSpeakerMcpTool() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", true));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        Object result = service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35));

        assertEquals(Map.of("success", true), result);
        ArgumentCaptor<DeviceDebugLogDraft> events = ArgumentCaptor.forClass(DeviceDebugLogDraft.class);
        InOrder order = inOrder(debugLogService, deviceService);
        order.verify(debugLogService).ingest(eq("device-a"), events.capture());
        order.verify(deviceService).callDeviceTool(
                "device-a", "self.audio_speaker.set_volume", Map.of("volume", 35));
        order.verify(debugLogService).ingest(eq("device-a"), events.capture());
        assertCommandEvent(events.getAllValues().get(0), "command.started", "info", "设备命令开始执行",
                "volume", 35, null);
        assertCommandEvent(events.getAllValues().get(1), "command.completed", "info", "设备命令执行完成",
                "volume", 35, true);
    }

    @Test
    void unconfirmedCommandReturnsTypedOfflineFailure() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.screen.set_brightness", Map.of("brightness", 60)))
                .thenReturn(null);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_OFFLINE)).thenReturn("offline");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("brightness", 60)));
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.getCode());
        assertNull(error.getCause());
        assertFailedEvent(debugLogService, "brightness", 60);
    }

    @Test
    void gatewayFailureReturnsTypedOfflineFailure() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        IllegalStateException gatewayFailure = new IllegalStateException("gateway timeout");
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenThrow(gatewayFailure);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_OFFLINE)).thenReturn("offline");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.getCode());
        assertSame(gatewayFailure, error.getCause());
        assertFailedEvent(debugLogService, "volume", 35);
    }

    @Test
    void gatewayRenExceptionIsStillWrappedAsOfflineFailure() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());

        RenException gatewayFailure;
        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.PARAM_TYPE_INVALID)).thenReturn("invalid");
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_OFFLINE)).thenReturn("offline");
            gatewayFailure = new RenException(ErrorCode.PARAM_TYPE_INVALID);
            when(deviceService.callDeviceTool(
                    "device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                    .thenThrow(gatewayFailure);
            CompanionDeviceService service = new CompanionDeviceServiceImpl(
                    deviceService, mock(CompanionProfileService.class), debugLogService);

            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.getCode());
        assertSame(gatewayFailure, error.getCause());
        assertFailedEvent(debugLogService, "volume", 35);
    }

    @Test
    void startedEventDelayIsExcludedFromCommandDuration() throws Exception {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", true));
        doAnswer(invocation -> {
            DeviceDebugLogDraft event = invocation.getArgument(1);
            if ("command.started".equals(event.eventType())) {
                Thread.sleep(150);
            }
            return null;
        }).when(debugLogService).ingest(eq("device-a"), any(DeviceDebugLogDraft.class));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35));

        ArgumentCaptor<DeviceDebugLogDraft> events = ArgumentCaptor.forClass(DeviceDebugLogDraft.class);
        verify(debugLogService, times(2)).ingest(eq("device-a"), events.capture());
        assertTrue(events.getAllValues().get(1).durationMs() < 100);
    }

    @Test
    void recentHeartbeatMarksDeviceOnlineEvenWhenToolInspectionFails() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceEntity device = ownedDevice();
        device.setLastConnectedAt(new Date(System.currentTimeMillis() - 120_000));
        when(deviceService.selectById("device-a")).thenReturn(device);
        when(deviceService.getDeviceTools("device-a")).thenThrow(new IllegalStateException("gateway timeout"));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), mock(DeviceDebugLogService.class));

        CompanionDeviceVO result = service.get(7L, "device-a");

        assertEquals(true, result.getOnline());
    }

    @Test
    void staleHeartbeatMarksDeviceOfflineEvenWhenToolsAreAvailable() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceEntity device = ownedDevice();
        device.setLastConnectedAt(new Date(System.currentTimeMillis() - 180_000));
        when(deviceService.selectById("device-a")).thenReturn(device);
        when(deviceService.getDeviceTools("device-a")).thenReturn(Map.of("tools", List.of()));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), mock(DeviceDebugLogService.class));

        CompanionDeviceVO result = service.get(7L, "device-a");

        assertEquals(false, result.getOnline());
    }

    @Test
    void missingHeartbeatMarksDeviceOffline() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), mock(DeviceDebugLogService.class));

        assertEquals(false, service.get(7L, "device-a").getOnline());
    }

    @Test
    void detailDefaultsNullDebugLogSwitchToFalse() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), mock(DeviceDebugLogService.class));

        CompanionDeviceVO result = service.get(7L, "device-a");

        assertEquals(false, result.getDebugLogEnabled());
    }

    @Test
    void explicitMcpFailureDoesNotReturnSuccess() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", false));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_COMMAND_FAILED)).thenReturn("failed");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        assertEquals(ErrorCode.DEVICE_COMMAND_FAILED, error.getCode());
        assertNull(error.getCause());
        assertFailedEvent(debugLogService, "volume", 35);
    }

    @Test
    void debugLogFailureDoesNotChangeSuccessfulCommandResult() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", true));
        doThrow(new IllegalStateException("debug unavailable")).when(debugLogService)
                .ingest(eq("device-a"), any(DeviceDebugLogDraft.class));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        Object result = service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35));

        assertEquals(Map.of("success", true), result);
        verify(deviceService).callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35));
    }

    @Test
    void completedDebugLogFailureDoesNotChangeSuccessfulCommandResult() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", true));
        doThrow(new IllegalStateException("debug unavailable")).when(debugLogService)
                .ingest(eq("device-a"),
                        org.mockito.ArgumentMatchers.argThat(event -> "command.completed".equals(event.eventType())));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        Object result = service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35));

        assertEquals(Map.of("success", true), result);
    }

    @Test
    void debugLogFailureDoesNotChangeCommandFailureCode() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", false));
        doThrow(new IllegalStateException("debug unavailable")).when(debugLogService)
                .ingest(eq("device-a"), any(DeviceDebugLogDraft.class));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_COMMAND_FAILED)).thenReturn("failed");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        assertEquals(ErrorCode.DEVICE_COMMAND_FAILED, error.getCode());
    }
    @Test
    void ownershipFailureDoesNotEmitCommandStarted() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceDebugLogService debugLogService = mock(DeviceDebugLogService.class);
        when(deviceService.selectById("device-a")).thenReturn(null);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), debugLogService);

        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_NOT_EXIST)).thenReturn("missing");
            assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        verify(debugLogService, never()).ingest(any(), any());
        verify(deviceService, never()).callDeviceTool(any(), any(), any());
    }

    @Test
    void renameWritesOnlyAllowedFieldsWithOwnershipPredicate() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.update(any(DeviceEntity.class), any())).thenReturn(true);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), mock(DeviceDebugLogService.class));
        DeviceUpdateDTO update = new DeviceUpdateDTO();
        update.setAlias("bedroom");

        service.update(7L, "device-a", update);

        ArgumentCaptor<DeviceEntity> changed = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceService).update(changed.capture(), any());
        assertEquals("device-a", changed.getValue().getId());
        assertEquals("bedroom", changed.getValue().getAlias());
        assertNull(changed.getValue().getUserId());
        assertNull(changed.getValue().getAgentId());
    }

    @Test
    void ownerEnablesDebugLogsWithoutChangingAliasOrAgent() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.update(any(DeviceEntity.class), any())).thenReturn(true);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class), mock(DeviceDebugLogService.class));

        service.setDebugLogEnabled(7L, "device-a", true);

        ArgumentCaptor<DeviceEntity> changed = ArgumentCaptor.forClass(DeviceEntity.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<DeviceEntity>> predicate = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(deviceService).update(changed.capture(), predicate.capture());
        assertEquals("device-a", changed.getValue().getId());
        assertEquals(1, changed.getValue().getDebugLogEnabled());
        assertNull(changed.getValue().getAlias());
        assertNull(changed.getValue().getAgentId());
        String sqlSegment = predicate.getValue().getExpression().getNormal().getSqlSegment();
        assertTrue(sqlSegment.contains("id ="));
        assertTrue(sqlSegment.contains("user_id ="));
        assertTrue(predicate.getValue().getParamNameValuePairs().containsValue("device-a"));
        assertTrue(predicate.getValue().getParamNameValuePairs().containsValue(7L));
    }

    private CompanionProfileVO profile(String id) {
        CompanionProfileVO profile = new CompanionProfileVO();
        profile.setId(id);
        return profile;
    }

    private void assertFailedEvent(DeviceDebugLogService debugLogService, String command, int value) {
        ArgumentCaptor<DeviceDebugLogDraft> events = ArgumentCaptor.forClass(DeviceDebugLogDraft.class);
        verify(debugLogService, times(2)).ingest(eq("device-a"), events.capture());
        assertCommandEvent(events.getAllValues().get(0), "command.started", "info", "设备命令开始执行",
                command, value, null);
        assertCommandEvent(events.getAllValues().get(1), "command.failed", "error", "设备命令执行失败",
                command, value, true);
    }

    private void assertCommandEvent(DeviceDebugLogDraft event, String eventType, String level, String summary,
            String command, int value, Boolean hasDuration) {
        assertNull(event.sessionId());
        assertNull(event.sentenceId());
        assertEquals("device", event.category());
        assertEquals(eventType, event.eventType());
        assertEquals(level, event.level());
        assertEquals(summary, event.summary());
        assertEquals(Map.of("command", command, "value", value), event.details());
        assertNull(event.occurredAt());
        if (hasDuration == null) {
            assertNull(event.durationMs());
        } else {
            assertTrue(event.durationMs() >= 0);
        }
    }

    private DeviceEntity ownedDevice() {
        DeviceEntity device = new DeviceEntity();
        device.setId("device-a");
        device.setUserId(7L);
        return device;
    }
}
