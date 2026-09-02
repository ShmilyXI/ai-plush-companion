package xiaozhi.modules.companion.controller;

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
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.dto.AppProfileCreateDTO;
import xiaozhi.modules.companion.dto.AppProfileSaveDTO;
import xiaozhi.modules.companion.service.AppProfileFacade;
import xiaozhi.modules.companion.vo.AppCapabilityOptionVO;
import xiaozhi.modules.companion.vo.CompanionProfileVO;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
@RequestMapping("/app/profiles")
public class AppProfileController {
    private final AppProfileFacade facade;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionProfileVO>> list() {
        return new Result<List<CompanionProfileVO>>().ok(facade.list(SecurityUser.getUserId()));
    }

    @GetMapping("/templates")
    @RequiresPermissions("sys:role:normal")
    public Result<List<java.util.Map<String, Object>>> templates() {
        return new Result<List<java.util.Map<String, Object>>>().ok(facade.templates());
    }

    @GetMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<CompanionProfileVO> get(@PathVariable String id) {
        return new Result<CompanionProfileVO>().ok(facade.get(SecurityUser.getUserId(), id));
    }

    @GetMapping("/{id}/model-options")
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionModelOptionVO>> modelOptions(@PathVariable String id) {
        return new Result<List<CompanionModelOptionVO>>().ok(facade.modelOptions(SecurityUser.getUserId(), id));
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<String> create(@RequestBody @Valid AppProfileCreateDTO request) {
        return new Result<String>().ok(facade.create(SecurityUser.getUserId(), request));
    }

    @PutMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> save(@PathVariable String id, @RequestBody @Valid AppProfileSaveDTO request) {
        facade.save(SecurityUser.getUserId(), id, request);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/{id}/memory-settings")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> memorySettings(@PathVariable String id, @RequestBody MemorySettingsRequest request) {
        if (request == null || request.enabled == null) throw new IllegalArgumentException("enabled is required");
        facade.setMemoryEnabled(SecurityUser.getUserId(), id, request.enabled);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}/capability-options")
    @RequiresPermissions("sys:role:normal")
    public Result<List<AppCapabilityOptionVO>> capabilityOptions(@PathVariable String id) {
        return new Result<List<AppCapabilityOptionVO>>().ok(facade.capabilityOptions(SecurityUser.getUserId(), id));
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String id) {
        facade.delete(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    public static class MemorySettingsRequest {
        public Boolean enabled;
    }
}
