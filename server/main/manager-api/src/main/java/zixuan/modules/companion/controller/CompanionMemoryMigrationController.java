package zixuan.modules.companion.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import zixuan.common.utils.Result;
import zixuan.modules.device.service.CompanionMemoryService;
import zixuan.modules.device.service.CompanionMemoryService.MigrationPreview;
import zixuan.modules.device.vo.CompanionMemoryMigrationVO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/memory-migrations")
@RequiresPermissions("sys:role:normal")
public class CompanionMemoryMigrationController {
    private final CompanionMemoryService memoryService;

    public CompanionMemoryMigrationController(CompanionMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/preview")
    public Result<MigrationPreview> preview(@RequestParam String sourceDeviceId,
            @RequestParam String targetDeviceId) {
        Long userId = SecurityUser.getUserId();
        return new Result<MigrationPreview>().ok(memoryService.preview(userId, userId, sourceDeviceId, targetDeviceId));
    }

    @PostMapping
    public Result<CompanionMemoryMigrationVO> migrate(@RequestBody @Valid MigrationRequest request) {
        Long userId = SecurityUser.getUserId();
        return new Result<CompanionMemoryMigrationVO>().ok(memoryService.migrate(userId, userId,
                request.sourceDeviceId(), request.targetDeviceId(), request.mode()));
    }

    @GetMapping
    public Result<List<CompanionMemoryMigrationVO>> history() {
        Long userId = SecurityUser.getUserId();
        return new Result<List<CompanionMemoryMigrationVO>>().ok(memoryService.history(userId, 50));
    }

    @PostMapping("/{migrationId}/retry")
    public Result<CompanionMemoryMigrationVO> retry(@org.springframework.web.bind.annotation.PathVariable String migrationId) {
        Long userId = SecurityUser.getUserId();
        return new Result<CompanionMemoryMigrationVO>().ok(memoryService.retry(userId, userId, migrationId));
    }

    public record MigrationRequest(@NotBlank String sourceDeviceId, @NotBlank String targetDeviceId,
            @NotBlank String mode) {
    }
}
