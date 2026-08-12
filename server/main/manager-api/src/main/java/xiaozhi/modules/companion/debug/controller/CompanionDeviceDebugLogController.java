package xiaozhi.modules.companion.debug.controller;

import java.util.concurrent.ExecutorService;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.debug.service.DeviceDebugLogService;
import xiaozhi.modules.companion.debug.vo.DeviceDebugLogHistoryVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/devices")
public class CompanionDeviceDebugLogController {
    private final DeviceDebugLogService service;
    private final ExecutorService streamExecutor;

    public CompanionDeviceDebugLogController(DeviceDebugLogService service,
            @Qualifier("deviceDebugLogStreamExecutor") ExecutorService streamExecutor) {
        this.service = service;
        this.streamExecutor = streamExecutor;
    }

    @GetMapping("/{id}/debug-logs")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceDebugLogHistoryVO> history(@PathVariable String id) {
        return new Result<DeviceDebugLogHistoryVO>().ok(service.history(SecurityUser.getUserId(), id));
    }

    @GetMapping(value = "/{id}/debug-logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermissions("sys:role:normal")
    public SseEmitter stream(@PathVariable String id,
            @RequestParam(value = "after", defaultValue = "0-0") String after) {
        service.requireOwned(SecurityUser.getUserId(), id);
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.execute(() -> service.stream(id, after, emitter));
        return emitter;
    }
}
