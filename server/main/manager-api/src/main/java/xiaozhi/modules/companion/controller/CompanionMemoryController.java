package xiaozhi.modules.companion.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.service.CompanionMemoryService;
import xiaozhi.modules.device.service.CompanionMemoryService.MemoryItem;
import xiaozhi.modules.device.service.CompanionMemoryService.MigrationPreview;
import xiaozhi.modules.device.vo.CompanionMemoryMigrationVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/devices/{deviceId}/memories")
@RequiresPermissions("sys:role:normal")
public class CompanionMemoryController {
    private final CompanionMemoryService memoryService;

    public CompanionMemoryController(CompanionMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public Result<List<MemoryItem>> list(@PathVariable String deviceId) {
        Long userId = SecurityUser.getUserId();
        return new Result<List<MemoryItem>>().ok(memoryService.list(userId, userId, deviceId));
    }

    @PutMapping("/{memoryId}")
    public Result<Void> update(@PathVariable String deviceId, @PathVariable String memoryId,
            @RequestBody @Valid MemoryUpdateRequest request) {
        Long userId = SecurityUser.getUserId();
        memoryService.update(userId, userId, deviceId, memoryId, request.content());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{memoryId}")
    public Result<Void> delete(@PathVariable String deviceId, @PathVariable String memoryId) {
        Long userId = SecurityUser.getUserId();
        memoryService.delete(userId, userId, deviceId, memoryId);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping
    public Result<Void> clear(@PathVariable String deviceId) {
        Long userId = SecurityUser.getUserId();
        memoryService.clear(userId, userId, deviceId);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/migration-preview")
    public Result<MigrationPreview> preview(@RequestParam String sourceDeviceId,
            @RequestParam String targetDeviceId) {
        Long userId = SecurityUser.getUserId();
        return new Result<MigrationPreview>().ok(memoryService.preview(userId, userId, sourceDeviceId, targetDeviceId));
    }

    @PostMapping("/migrations")
    public Result<CompanionMemoryMigrationVO> migrate(@RequestBody @Valid MigrationRequest request) {
        Long userId = SecurityUser.getUserId();
        return new Result<CompanionMemoryMigrationVO>().ok(memoryService.migrate(userId, userId,
                request.sourceDeviceId(), request.targetDeviceId(), request.mode()));
    }

    @GetMapping("/migrations")
    public Result<List<CompanionMemoryMigrationVO>> history() {
        Long userId = SecurityUser.getUserId();
        return new Result<List<CompanionMemoryMigrationVO>>().ok(memoryService.history(userId, 50));
    }

    public record MemoryUpdateRequest(@NotBlank @Size(max = 4000) String content) {
    }

    public record MigrationRequest(@NotBlank String sourceDeviceId, @NotBlank String targetDeviceId,
            @NotBlank String mode) {
    }
}
