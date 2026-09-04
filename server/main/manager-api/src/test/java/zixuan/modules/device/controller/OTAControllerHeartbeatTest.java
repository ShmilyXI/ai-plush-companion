package zixuan.modules.device.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;

import zixuan.modules.device.service.DeviceService;
import zixuan.modules.device.dto.DeviceReportReqDTO;
import zixuan.modules.sys.service.SysParamsService;

class OTAControllerHeartbeatTest {

    @Test
    void heartbeatUpdatesAnExistingBoundDevice() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.verifyDeviceToken("signed", "client-1", "9c:13:9e:8a:14:a4")).thenReturn(true);
        when(deviceService.touchHeartbeat("9c:13:9e:8a:14:a4")).thenReturn(true);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat(
                "9c:13:9e:8a:14:a4", "client-1", "Bearer signed", null);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(deviceService).touchHeartbeat("9c:13:9e:8a:14:a4");
    }

    @Test
    void heartbeatRejectsInvalidDeviceIdWithoutTouchingStorage() {
        DeviceService deviceService = mock(DeviceService.class);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat("not-a-mac", "client-1", "Bearer signed", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(deviceService, never()).touchHeartbeat(anyString());
    }

    @Test
    void heartbeatDoesNotCreateUnknownOrUnboundDevices() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.verifyDeviceToken("signed", "client-1", "9c:13:9e:8a:14:a4")).thenReturn(true);
        when(deviceService.touchHeartbeat("9c:13:9e:8a:14:a4")).thenReturn(false);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat(
                "9c:13:9e:8a:14:a4", "client-1", "Bearer signed", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void heartbeatRejectsForgedDeviceIdentity() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.touchHeartbeat("9c:13:9e:8a:14:a4")).thenReturn(true);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat(
                "9c:13:9e:8a:14:a4", "client-1", "Bearer forged", new DeviceReportReqDTO());

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(deviceService).touchHeartbeat("9c:13:9e:8a:14:a4");
        verify(deviceService, never()).reportWakeWordState(anyString(), any());
    }

    @Test
    void otaReportDropsWakeWordStateWhenTokenIsForged() throws Exception {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.checkDeviceActive(anyString(), anyString(), any())).thenReturn(
                new zixuan.modules.device.dto.DeviceReportRespDTO());
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));
        DeviceReportReqDTO report = new ObjectMapper().readValue(
                "{\"wake_word\":{\"supported\":true,\"layout_version\":2,\"slot_size\":3145728}}",
                DeviceReportReqDTO.class);

        controller.checkOTAVersion(report, "9c:13:9e:8a:14:a4", "client-1", "Bearer forged");

        org.junit.jupiter.api.Assertions.assertNull(report.getWakeWord());
    }
}
