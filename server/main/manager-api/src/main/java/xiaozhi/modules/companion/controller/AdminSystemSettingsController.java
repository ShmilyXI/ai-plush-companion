package xiaozhi.modules.companion.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.dto.AdminSystemSettingsSaveDTO;
import xiaozhi.modules.companion.service.AdminSystemSettingsService;
import xiaozhi.modules.companion.vo.AdminSystemSettingsVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/admin/companion/system-settings")
@AllArgsConstructor
public class AdminSystemSettingsController {
    private final AdminSystemSettingsService settingsService;

    @GetMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<AdminSystemSettingsVO> get() {
        return new Result<AdminSystemSettingsVO>().ok(settingsService.get());
    }

    @PutMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<AdminSystemSettingsVO> save(@RequestBody @Valid AdminSystemSettingsSaveDTO request) {
        return new Result<AdminSystemSettingsVO>().ok(settingsService.save(SecurityUser.getUserId(), request));
    }
}
