package zixuan.modules.companion.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.companion.vo.CompanionEntitlementVO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
public class CompanionSubscriptionController {
    private final CompanionSubscriptionService subscriptionService;

    @GetMapping("/companion/subscription")
    @RequiresPermissions("sys:role:normal")
    public Result<CompanionEntitlementVO> current() {
        return new Result<CompanionEntitlementVO>().ok(subscriptionService.current(SecurityUser.getUserId()));
    }

}
