package zixuan.modules.companion.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.companion.dto.CompanionProfileCreateDTO;
import zixuan.modules.companion.dto.CompanionProfileSaveDTO;
import zixuan.modules.companion.service.CompanionProfileService;
import zixuan.modules.companion.vo.CompanionProfileVO;
import zixuan.modules.companion.model.vo.CompanionModelOptionVO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
@RequestMapping("/companion/profiles")
public class CompanionProfileController {
    private final CompanionProfileService profileService;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionProfileVO>> list() {
        return new Result<List<CompanionProfileVO>>().ok(profileService.list(SecurityUser.getUserId()));
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<String> create(@RequestBody @Valid CompanionProfileCreateDTO dto) {
        return new Result<String>().ok(
                profileService.createFromTemplate(SecurityUser.getUserId(), dto.getTemplateId(), dto.getName()));
    }

    @GetMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<CompanionProfileVO> get(@PathVariable String id) {
        return new Result<CompanionProfileVO>().ok(profileService.get(SecurityUser.getUserId(), id));
    }

    @GetMapping("/{id}/model-options")
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionModelOptionVO>> modelOptions(@PathVariable String id) {
        return new Result<List<CompanionModelOptionVO>>().ok(profileService.modelOptions(SecurityUser.getUserId(), id));
    }

    @PutMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> update(
            @PathVariable String id,
            @RequestBody @Valid CompanionProfileSaveDTO dto) {
        profileService.update(SecurityUser.getUserId(), id, dto);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/{id}/restore-prompt")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> restorePrompt(@PathVariable String id) {
        profileService.restorePrompt(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String id) {
        profileService.delete(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }
}
