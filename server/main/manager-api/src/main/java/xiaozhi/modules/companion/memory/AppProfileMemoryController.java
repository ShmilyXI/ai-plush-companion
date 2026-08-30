package xiaozhi.modules.companion.memory;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
@RequestMapping("/companion/profiles/{profileId}/memories")
@RequiresPermissions("sys:role:normal")
public class AppProfileMemoryController {
    private final ProfileMemoryService memoryService;

    @GetMapping
    public Result<ProfileMemoryService.MemoryView> list(@PathVariable String profileId) {
        return new Result<ProfileMemoryService.MemoryView>().ok(memoryService.list(SecurityUser.getUserId(), profileId));
    }

    @PutMapping("/{memoryId}")
    public Result<Void> update(@PathVariable String profileId, @PathVariable String memoryId,
            @RequestBody @Valid MemoryUpdateRequest request) {
        memoryService.update(SecurityUser.getUserId(), profileId, memoryId, request.content());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{memoryId}")
    public Result<Void> delete(@PathVariable String profileId, @PathVariable String memoryId) {
        memoryService.delete(SecurityUser.getUserId(), profileId, memoryId);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping
    public Result<Void> clear(@PathVariable String profileId) {
        memoryService.clear(SecurityUser.getUserId(), profileId);
        return new Result<Void>().ok(null);
    }

    public record MemoryUpdateRequest(@NotBlank @Size(max = 4000) String content) {
    }
}
