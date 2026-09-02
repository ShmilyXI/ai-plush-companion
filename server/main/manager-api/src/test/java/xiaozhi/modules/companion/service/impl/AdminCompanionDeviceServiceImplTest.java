package xiaozhi.modules.companion.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

class AdminCompanionDeviceServiceImplTest {
    @Test
    void renameChangesOnlyAliasAndAuditsAfterSuccessfulUpdate() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        when(deviceService.selectById("d1")).thenReturn(device());
        when(deviceService.updateById(any(DeviceEntity.class))).thenReturn(true);
        AdminCompanionDeviceServiceImpl service = new AdminCompanionDeviceServiceImpl(deviceService, auditService);

        service.rename(1L, "d1", "  床头伙伴  ");

        ArgumentCaptor<DeviceEntity> update = ArgumentCaptor.forClass(DeviceEntity.class);
        InOrder order = inOrder(deviceService, auditService);
        order.verify(deviceService).selectById("d1");
        order.verify(deviceService).updateById(update.capture());
        order.verify(auditService).record(1L, 9L, "device.update", "device", "d1",
                Map.of("alias", "床头伙伴"));
        assertEquals("d1", update.getValue().getId());
        assertEquals("床头伙伴", update.getValue().getAlias());
        assertNull(update.getValue().getMacAddress());
        assertNull(update.getValue().getUserId());
    }

    @Test
    void unbindUsesTheResolvedOwnerAndAuditsAfterSuccess() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        when(deviceService.selectById("d1")).thenReturn(device());
        AdminCompanionDeviceServiceImpl service = new AdminCompanionDeviceServiceImpl(deviceService, auditService);

        service.unbind(1L, "d1");

        InOrder order = inOrder(deviceService, auditService);
        order.verify(deviceService).selectById("d1");
        order.verify(deviceService).unbindDevice(9L, "d1");
        order.verify(auditService).record(1L, 9L, "device.unbind", "device", "d1",
                Map.of("ownerId", 9L));
    }

    @Test
    void updateModeChangesOnlyCompanionModeAndAuditsOldAndNewValues() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        DeviceEntity existing = device();
        existing.setCompanionMode("turn_based");
        when(deviceService.selectById("d1")).thenReturn(existing);
        when(deviceService.updateById(any(DeviceEntity.class))).thenReturn(true);
        AdminCompanionDeviceServiceImpl service = new AdminCompanionDeviceServiceImpl(deviceService, auditService);

        service.updateMode(1L, "d1", "proactive");

        ArgumentCaptor<DeviceEntity> update = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceService).updateById(update.capture());
        verify(auditService).record(1L, 9L, "device.mode.update", "device", "d1",
                Map.of("before", "turn_based", "after", "proactive"));
        assertEquals("d1", update.getValue().getId());
        assertEquals("proactive", update.getValue().getCompanionMode());
        assertNull(update.getValue().getAlias());
    }

    @Test
    void updateModeRejectsUnsupportedValues() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        when(deviceService.selectById("d1")).thenReturn(device());
        AdminCompanionDeviceServiceImpl service = new AdminCompanionDeviceServiceImpl(deviceService, auditService);

        assertThrows(RenException.class, () -> service.updateMode(1L, "d1", "unknown"));
        verify(deviceService, never()).updateById(any(DeviceEntity.class));
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingOrFailedDevicesDoNotWriteSuccessAudit() {
        DeviceService deviceService = mock(DeviceService.class);
        CompanionAuditService auditService = mock(CompanionAuditService.class);
        AdminCompanionDeviceServiceImpl service = new AdminCompanionDeviceServiceImpl(deviceService, auditService);

        assertThrows(RenException.class, () -> service.rename(1L, "missing", "名字"));
        assertThrows(RenException.class, () -> service.unbind(1L, "missing"));
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());

        when(deviceService.selectById("d1")).thenReturn(device());
        when(deviceService.updateById(any(DeviceEntity.class))).thenReturn(false);
        assertThrows(RenException.class, () -> service.rename(1L, "d1", "名字"));
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    private DeviceEntity device() {
        DeviceEntity device = new DeviceEntity();
        device.setId("d1");
        device.setUserId(9L);
        device.setMacAddress("AA:BB");
        device.setAlias("旧名称");
        return device;
    }
}
