package xiaozhi.modules.device.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.service.SysParamsService;

class OTAControllerHeartbeatTest {

    @Test
    void heartbeatUpdatesAnExistingBoundDevice() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.touchHeartbeat("9c:13:9e:8a:14:a4")).thenReturn(true);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat("9c:13:9e:8a:14:a4");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(deviceService).touchHeartbeat("9c:13:9e:8a:14:a4");
    }

    @Test
    void heartbeatRejectsInvalidDeviceIdWithoutTouchingStorage() {
        DeviceService deviceService = mock(DeviceService.class);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat("not-a-mac");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(deviceService, never()).touchHeartbeat(anyString());
    }

    @Test
    void heartbeatDoesNotCreateUnknownOrUnboundDevices() {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.touchHeartbeat("9c:13:9e:8a:14:a4")).thenReturn(false);
        OTAController controller = new OTAController(deviceService, mock(SysParamsService.class));

        ResponseEntity<Void> response = controller.heartbeat("9c:13:9e:8a:14:a4");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
