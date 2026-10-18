package zixuan.modules.companion.debug.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import zixuan.common.utils.Result;
import zixuan.modules.companion.debug.dto.DeviceDebugLogIngestDTO;
import zixuan.modules.companion.debug.service.DeviceDebugLogService;

@RestController
@RequestMapping("/internal/device-debug-logs")
public class InternalDeviceDebugLogController {
    private final DeviceDebugLogService service;

    public InternalDeviceDebugLogController(DeviceDebugLogService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public Result<Void> ingest(@RequestBody @Valid DeviceDebugLogIngestDTO dto) {
        service.ingest(dto.getDeviceRef(), dto.toDraft());
        return new Result<Void>().ok(null);
    }
}
