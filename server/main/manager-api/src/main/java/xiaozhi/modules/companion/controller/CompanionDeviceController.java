package xiaozhi.modules.companion.controller;

import java.util.List;
import java.util.Map;

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
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.dto.CompanionDeviceBindDTO;
import xiaozhi.modules.companion.dto.CompanionDeviceCommandDTO;
import xiaozhi.modules.companion.service.CompanionDeviceService;
import xiaozhi.modules.companion.vo.CompanionDeviceVO;
import xiaozhi.modules.device.dto.DeviceUpdateDTO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/devices")
public class CompanionDeviceController {
    private final CompanionDeviceService deviceService;

    public CompanionDeviceController(CompanionDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionDeviceVO>> list() {
        return new Result<List<CompanionDeviceVO>>().ok(deviceService.list(SecurityUser.getUserId()));
    }

    @PostMapping("/bind")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> bind(@RequestBody @Valid CompanionDeviceBindDTO dto) {
        deviceService.bind(SecurityUser.getUserId(), dto);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<CompanionDeviceVO> get(@PathVariable String id) {
        return new Result<CompanionDeviceVO>().ok(deviceService.get(SecurityUser.getUserId(), id));
    }

    @PutMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> update(@PathVariable String id, @RequestBody @Valid DeviceUpdateDTO dto) {
        deviceService.update(SecurityUser.getUserId(), id, dto);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/{id}/profile")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> switchProfile(@PathVariable String id, @RequestBody Map<String, String> request) {
        String profileId = request.get("profileId");
        if (profileId == null || profileId.isBlank()) {
            throw new RenException(ErrorCode.PARAM_VALUE_NULL);
        }
        deviceService.switchProfile(SecurityUser.getUserId(), id, profileId);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> unbind(@PathVariable String id) {
        deviceService.unbind(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/{id}/commands")
    @RequiresPermissions("sys:role:normal")
    public Result<Object> command(@PathVariable String id, @RequestBody @Valid CompanionDeviceCommandDTO dto) {
        return new Result<>().ok(deviceService.command(SecurityUser.getUserId(), id, dto));
    }
}
