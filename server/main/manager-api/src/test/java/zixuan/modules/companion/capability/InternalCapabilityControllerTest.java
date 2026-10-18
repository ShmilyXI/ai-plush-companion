package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import zixuan.modules.companion.capability.controller.InternalCapabilityController;
import zixuan.modules.companion.capability.dto.DeviceToolSnapshotSaveDTO;
import zixuan.modules.companion.capability.service.InternalCapabilityService;
import zixuan.modules.companion.capability.vo.EffectiveCapabilityBundleVO;

class InternalCapabilityControllerTest {

    @Test
    void exposesRuntimeBundleToolSnapshotAndSecretRoutes() throws Exception {
        assertNotNull(InternalCapabilityController.class.getMethod("bundle", String.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(InternalCapabilityController.class.getMethod("saveTools", String.class,
                DeviceToolSnapshotSaveDTO.class).getAnnotation(PostMapping.class));
        assertNotNull(InternalCapabilityController.class.getMethod("secret", String.class, String.class)
                .getAnnotation(GetMapping.class));
    }

    @Test
    void delegatesUsingOnlyTheDeviceAndSecretIdentifiers() {
        InternalCapabilityService service = mock(InternalCapabilityService.class);
        InternalCapabilityController controller = new InternalCapabilityController(service);
        EffectiveCapabilityBundleVO bundle = new EffectiveCapabilityBundleVO();
        bundle.setDeviceId("device-1");
        when(service.bundle("device-1")).thenReturn(bundle);
        when(service.secret("device-1", "secret-weather")).thenReturn("weather-key");
        DeviceToolSnapshotSaveDTO request = request();

        assertEquals("device-1", controller.bundle("device-1").getData().getDeviceId());
        controller.saveTools("device-1", request);
        assertEquals(Map.of("value", "weather-key"),
                controller.secret("secret-weather", "device-1").getData());

        verify(service).saveDeviceTools("device-1", request);
        verify(service).secret("device-1", "secret-weather");
    }

    private DeviceToolSnapshotSaveDTO request() {
        DeviceToolSnapshotSaveDTO.ToolDTO tool = new DeviceToolSnapshotSaveDTO.ToolDTO();
        tool.setName("self.audio_speaker.set_volume");
        tool.setInputSchema(Map.of("type", "object"));
        tool.setAvailable(true);
        DeviceToolSnapshotSaveDTO dto = new DeviceToolSnapshotSaveDTO();
        dto.setDeviceModel("esp32-s3");
        dto.setFirmwareVersion("1.2.3");
        dto.setTools(List.of(tool));
        return dto;
    }
}
