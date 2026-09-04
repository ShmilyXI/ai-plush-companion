package zixuan.modules.companion.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.Result;
import zixuan.modules.companion.dto.CompanionDeviceBindDTO;
import zixuan.modules.companion.dto.CompanionDeviceCommandDTO;
import zixuan.modules.companion.debug.dto.DeviceDebugLogSettingDTO;
import zixuan.modules.companion.service.CompanionDeviceService;
import zixuan.modules.companion.capability.dto.DeviceSkillBindingDTO;
import zixuan.modules.companion.capability.service.DeviceCapabilityService;
import zixuan.modules.companion.capability.vo.DeviceSkillBindingVO;
import zixuan.modules.companion.capability.vo.DeviceSkillCatalogVO;
import zixuan.modules.companion.vo.CompanionDeviceVO;
import zixuan.modules.companion.wakeword.dto.DeviceWakeWordUpdateDTO;
import zixuan.modules.companion.wakeword.service.DeviceWakeWordService;
import zixuan.modules.companion.wakeword.vo.DeviceWakeWordVO;
import zixuan.modules.device.dto.DeviceUpdateDTO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/devices")
public class CompanionDeviceController {
    private final CompanionDeviceService deviceService;
    private final DeviceWakeWordService wakeWordService;
    private final DeviceCapabilityService capabilityService;

    public CompanionDeviceController(CompanionDeviceService deviceService, DeviceWakeWordService wakeWordService) {
        this(deviceService, wakeWordService, null);
    }

    @Autowired
    public CompanionDeviceController(CompanionDeviceService deviceService, DeviceWakeWordService wakeWordService,
            DeviceCapabilityService capabilityService) {
        this.deviceService = deviceService;
        this.wakeWordService = wakeWordService;
        this.capabilityService = capabilityService;
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

    @PutMapping("/{id}/debug-logs/settings")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> setDebugLogEnabled(@PathVariable String id,
            @RequestBody @Valid DeviceDebugLogSettingDTO dto) {
        deviceService.setDebugLogEnabled(SecurityUser.getUserId(), id, dto.getEnabled());
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

    @GetMapping("/{id}/wake-word")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceWakeWordVO> getWakeWord(@PathVariable String id) {
        return new Result<DeviceWakeWordVO>().ok(wakeWordService.get(SecurityUser.getUserId(), id));
    }

    @PutMapping("/{id}/wake-word")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceWakeWordVO> updateWakeWord(@PathVariable String id,
            @RequestBody @Valid DeviceWakeWordUpdateDTO dto) {
        return new Result<DeviceWakeWordVO>().ok(
                wakeWordService.update(SecurityUser.getUserId(), id, dto.getWord()));
    }

    @PostMapping("/{id}/wake-word/retry")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceWakeWordVO> retryWakeWord(@PathVariable String id) {
        return new Result<DeviceWakeWordVO>().ok(wakeWordService.retry(SecurityUser.getUserId(), id));
    }

    @GetMapping("/{id}/skills")
    @RequiresPermissions("sys:role:normal")
    public Result<List<DeviceSkillBindingVO>> skills(@PathVariable String id) {
        return new Result<List<DeviceSkillBindingVO>>().ok(
                capabilityService.list(SecurityUser.getUserId(), id, false));
    }

    @GetMapping("/{id}/skills/catalog")
    @RequiresPermissions("sys:role:normal")
    public Result<List<DeviceSkillCatalogVO>> skillCatalog(@PathVariable String id) {
        return new Result<List<DeviceSkillCatalogVO>>().ok(
                capabilityService.catalog(SecurityUser.getUserId(), id, false));
    }

    @PutMapping("/{id}/skills")
    @RequiresPermissions("sys:role:normal")
    public Result<List<DeviceSkillBindingVO>> saveSkills(@PathVariable String id,
            @RequestBody List<@Valid DeviceSkillBindingDTO> request) {
        return new Result<List<DeviceSkillBindingVO>>().ok(
                capabilityService.save(SecurityUser.getUserId(), id, request, false));
    }
}
