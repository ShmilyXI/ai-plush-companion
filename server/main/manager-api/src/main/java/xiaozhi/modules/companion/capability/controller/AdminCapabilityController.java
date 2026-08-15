package xiaozhi.modules.companion.capability.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.CapabilitySecretSaveDTO;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.vo.CapabilityRoutePreviewVO;
import xiaozhi.modules.companion.capability.vo.CapabilityVO;
import xiaozhi.modules.companion.capability.vo.DeviceSkillBindingVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/admin/companion/capabilities")
public class AdminCapabilityController {
    private final CapabilityService capabilities;
    private final CapabilitySecretService secrets;
    private final CapabilityRoutePreviewService routePreview;
    private final DeviceCapabilityService deviceCapabilities;

    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview) {
        this(capabilities, secrets, routePreview, null);
    }

    @Autowired
    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview, DeviceCapabilityService deviceCapabilities) {
        this.capabilities = capabilities;
        this.secrets = secrets;
        this.routePreview = routePreview;
        this.deviceCapabilities = deviceCapabilities;
    }

    @GetMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<CapabilityVO>> page(@RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return new Result<PageData<CapabilityVO>>().ok(capabilities.page(type, status, keyword, page, limit));
    }

    @GetMapping("/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> get(@PathVariable String id) {
        return new Result<CapabilityVO>().ok(capabilities.get(id));
    }

    @PostMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> create(@RequestBody @Valid CapabilitySaveDTO request) {
        return new Result<CapabilityVO>().ok(capabilities.create(SecurityUser.getUserId(), request));
    }

    @PutMapping("/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> update(@PathVariable String id, @RequestBody @Valid CapabilitySaveDTO request) {
        return new Result<CapabilityVO>().ok(capabilities.update(SecurityUser.getUserId(), id, request));
    }

    @PostMapping("/{id}/publish")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> publish(@PathVariable String id) {
        return new Result<CapabilityVO>().ok(capabilities.publish(SecurityUser.getUserId(), id));
    }

    @PutMapping("/{id}/status")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> status(@PathVariable String id, @RequestBody @Valid StatusRequest request) {
        capabilities.updateStatus(SecurityUser.getUserId(), id, request.status());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> delete(@PathVariable String id) {
        capabilities.delete(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}/secrets")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Boolean>> secretStatus(@PathVariable String id) {
        return new Result<Map<String, Boolean>>().ok(secrets.status(id));
    }

    @PutMapping("/{id}/secrets/{name}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Boolean>> saveSecret(@PathVariable String id, @PathVariable String name,
            @RequestBody @Valid CapabilitySecretSaveDTO request) {
        boolean configured = secrets.save(SecurityUser.getUserId(), id, name, request.getValue());
        return new Result<Map<String, Boolean>>().ok(Map.of("configured", configured));
    }

    @PostMapping("/route-preview")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityRoutePreviewVO> routePreview(@RequestBody @Valid RoutePreviewRequest request) {
        return new Result<CapabilityRoutePreviewVO>().ok(routePreview.preview(request.deviceId(), request.utterance()));
    }

    @GetMapping("/devices/{deviceId}/skills")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<DeviceSkillBindingVO>> deviceSkills(@PathVariable String deviceId) {
        return new Result<List<DeviceSkillBindingVO>>().ok(
                deviceCapabilities.list(SecurityUser.getUserId(), deviceId, true));
    }

    @PutMapping("/devices/{deviceId}/skills")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<DeviceSkillBindingVO>> saveDeviceSkills(@PathVariable String deviceId,
            @RequestBody List<@Valid DeviceSkillBindingDTO> request) {
        return new Result<List<DeviceSkillBindingVO>>().ok(
                deviceCapabilities.save(SecurityUser.getUserId(), deviceId, request, true));
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record RoutePreviewRequest(@NotBlank String deviceId, @NotBlank String utterance) {
    }
}
