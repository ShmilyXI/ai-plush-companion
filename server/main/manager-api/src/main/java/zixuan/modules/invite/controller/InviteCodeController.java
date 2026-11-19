package zixuan.modules.invite.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.page.PageData;
import zixuan.common.utils.Result;
import zixuan.modules.invite.dto.InviteCodeCreateDTO;
import zixuan.modules.invite.dto.InviteCodeDTO;
import zixuan.modules.invite.service.InviteCodeService;
import zixuan.modules.security.user.SecurityUser;

@AllArgsConstructor
@RestController
@RequestMapping("/admin/invite-codes")
@Tag(name = "邀请码管理")
public class InviteCodeController {

    private final InviteCodeService inviteCodes;

    @GetMapping
    @Operation(summary = "邀请码列表")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<InviteCodeDTO>> page(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long limit) {
        return new Result<PageData<InviteCodeDTO>>().ok(inviteCodes.page(page, limit));
    }

    @PostMapping
    @Operation(summary = "生成邀请码")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<InviteCodeDTO>> create(@RequestBody(required = false) InviteCodeCreateDTO dto) {
        return new Result<List<InviteCodeDTO>>().ok(inviteCodes.create(dto, SecurityUser.getUserId()));
    }

    @PutMapping("/{id}/enabled/{enabled}")
    @Operation(summary = "启用或停用邀请码")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> setEnabled(@PathVariable String id, @PathVariable boolean enabled) {
        inviteCodes.setEnabled(id, enabled);
        return new Result<>();
    }

    @GetMapping("/required")
    @Operation(summary = "新账号是否必须填写邀请码")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Boolean>> required() {
        return new Result<Map<String, Boolean>>().ok(Map.of("required", inviteCodes.isRequired()));
    }

    @PutMapping("/required/{required}")
    @Operation(summary = "设置新账号是否必须填写邀请码")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> setRequired(@PathVariable boolean required) {
        inviteCodes.setRequired(required);
        return new Result<>();
    }
}
