package zixuan.modules.companion.screen.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.common.utils.Result;
import zixuan.modules.companion.screen.dao.ScreenSceneDao;
import zixuan.modules.companion.screen.entity.ScreenSceneEntity;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/companion/screen-scenes")
public class ScreenSceneController {
    private final ScreenSceneDao sceneDao;
    private final DeviceService deviceService;

    @GetMapping("/devices/{deviceId}")
    @RequiresPermissions("sys:role:normal")
    public Result<List<ScreenSceneEntity>> list(@PathVariable String deviceId) {
        requireOwned(deviceId);
        return new Result<List<ScreenSceneEntity>>().ok(sceneDao.selectByDevice(SecurityUser.getUserId(), deviceId));
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<ScreenSceneEntity> save(@RequestBody @Valid SceneInput input) {
        requireOwned(input.getDeviceId());
        ScreenSceneEntity entity = new ScreenSceneEntity();
        entity.setUserId(SecurityUser.getUserId());
        entity.setDeviceId(input.getDeviceId());
        entity.setSceneId(input.getSceneId());
        entity.setVersion(input.getVersion() == null ? 1 : input.getVersion());
        entity.setMode(input.getMode());
        entity.setSceneJson(input.getSceneJson());
        entity.setStatus("DRAFT");
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        if (sceneDao.insert(entity) != 1) throw new RenException("场景草稿保存失败");
        return new Result<ScreenSceneEntity>().ok(entity);
    }

    @PostMapping("/{id}/publish")
    @RequiresPermissions("sys:role:normal")
    public Result<ScreenSceneEntity> publish(@PathVariable String id) {
        ScreenSceneEntity entity = sceneDao.selectById(id);
        if (entity == null || !SecurityUser.getUserId().equals(entity.getUserId())) throw new RenException("场景不存在");
        entity.setStatus("PUBLISHED");
        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
        entity.setUpdatedAt(new Date());
        sceneDao.updateById(entity);
        if (deviceService.isOnline(entity.getDeviceId())) {
            deviceService.callDeviceTool(entity.getDeviceId(), "self.screen.set_mode", Map.of("mode", entity.getMode()));
        }
        return new Result<ScreenSceneEntity>().ok(entity);
    }

    private void requireOwned(String deviceId) {
        boolean owned = deviceService.getUserDevices(SecurityUser.getUserId()).stream()
                .map(DeviceEntity::getId).anyMatch(deviceId::equals);
        if (!owned) throw new RenException("设备不存在或无权限");
    }

    @Data
    public static class SceneInput {
        @NotBlank private String deviceId;
        @NotBlank private String sceneId;
        @NotBlank private String mode;
        @NotBlank private String sceneJson;
        private Integer version;
    }
}
