package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
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
        CompanionDeviceService service = new CompanionDeviceServiceImpl(deviceService, profileService);

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
        CompanionDeviceService service = new CompanionDeviceServiceImpl(deviceService, profileService);

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
            CompanionDeviceService service = new CompanionDeviceServiceImpl(deviceService, profileService);

            assertThrows(RenException.class,
                    () -> service.switchProfile(7L, "device-a", "profile-owned-by-8"));
        }

        verify(deviceService, never()).switchCompanionProfile(7L, "device-a", "profile-owned-by-8");
    }

    @Test
    void volumeCommandUsesSpeakerMcpTool() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", true));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));

        Object result = service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35));

        assertEquals(Map.of("success", true), result);
    }

    @Test
    void unconfirmedCommandReturnsTypedOfflineFailure() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.screen.set_brightness", Map.of("brightness", 60)))
                .thenReturn(null);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_OFFLINE)).thenReturn("offline");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("brightness", 60)));
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.getCode());
    }

    @Test
    void gatewayFailureReturnsTypedOfflineFailure() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenThrow(new IllegalStateException("gateway timeout"));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_OFFLINE)).thenReturn("offline");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.getCode());
    }

    @Test
    void recentHeartbeatMarksDeviceOnlineEvenWhenToolInspectionFails() {
        DeviceService deviceService = mock(DeviceService.class);
        DeviceEntity device = ownedDevice();
        device.setLastConnectedAt(new Date(System.currentTimeMillis() - 120_000));
        when(deviceService.selectById("device-a")).thenReturn(device);
        when(deviceService.getDeviceTools("device-a")).thenThrow(new IllegalStateException("gateway timeout"));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));

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
                deviceService, mock(CompanionProfileService.class));

        CompanionDeviceVO result = service.get(7L, "device-a");

        assertEquals(false, result.getOnline());
    }

    @Test
    void missingHeartbeatMarksDeviceOffline() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));

        assertEquals(false, service.get(7L, "device-a").getOnline());
    }

    @Test
    void explicitMcpFailureDoesNotReturnSuccess() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
                .thenReturn(Map.of("success", false));
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_COMMAND_FAILED)).thenReturn("failed");
            error = assertThrows(RenException.class,
                    () -> service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35)));
        }

        assertEquals(ErrorCode.DEVICE_COMMAND_FAILED, error.getCode());
    }

    @Test
    void renameWritesOnlyAllowedFieldsWithOwnershipPredicate() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
        when(deviceService.update(any(DeviceEntity.class), any())).thenReturn(true);
        CompanionDeviceService service = new CompanionDeviceServiceImpl(
                deviceService, mock(CompanionProfileService.class));
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

    private CompanionProfileVO profile(String id) {
        CompanionProfileVO profile = new CompanionProfileVO();
        profile.setId(id);
        return profile;
    }

    private DeviceEntity ownedDevice() {
        DeviceEntity device = new DeviceEntity();
        device.setId("device-a");
        device.setUserId(7L);
        return device;
    }
}
