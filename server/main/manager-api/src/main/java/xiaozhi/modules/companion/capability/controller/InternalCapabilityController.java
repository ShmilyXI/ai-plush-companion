package xiaozhi.modules.companion.capability.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.capability.dto.DeviceToolSnapshotSaveDTO;
import xiaozhi.modules.companion.capability.service.InternalCapabilityService;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO;

@RestController
@RequestMapping("/internal/capabilities")
@AllArgsConstructor
public class InternalCapabilityController {
    private final InternalCapabilityService service;

    @GetMapping("/devices/{deviceId}/bundle")
    public Result<EffectiveCapabilityBundleVO> bundle(@PathVariable String deviceId) {
        return new Result<EffectiveCapabilityBundleVO>().ok(service.bundle(deviceId));
    }

    @PostMapping("/devices/{deviceId}/tools")
    public Result<Void> saveTools(@PathVariable String deviceId,
            @RequestBody @Valid DeviceToolSnapshotSaveDTO request) {
        service.saveDeviceTools(deviceId, request);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/secrets/{secretId}")
    public Result<Map<String, String>> secret(@PathVariable String secretId, @RequestParam String deviceId) {
        return new Result<Map<String, String>>().ok(Map.of("value", service.secret(deviceId, secretId)));
    }
}
