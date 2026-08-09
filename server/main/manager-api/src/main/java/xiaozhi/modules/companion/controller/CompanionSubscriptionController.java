package xiaozhi.modules.companion.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.companion.vo.CompanionEntitlementVO;
import xiaozhi.modules.security.user.SecurityUser;

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
