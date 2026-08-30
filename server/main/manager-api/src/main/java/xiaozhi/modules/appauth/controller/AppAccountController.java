package xiaozhi.modules.appauth.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.appauth.AppAccountVO;
import xiaozhi.modules.appauth.AppAuthService;
import xiaozhi.modules.appauth.AppBindContactRequest;
import xiaozhi.modules.appauth.AppChangePasswordRequest;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
@RequestMapping("/app/account")
@RequiresPermissions("sys:role:normal")
public class AppAccountController {
    private final AppAuthService auth;

    @GetMapping
    public Result<AppAccountVO> account() {
        return new Result<AppAccountVO>().ok(auth.account(SecurityUser.getUserId()));
    }

    @PostMapping("/contacts")
    public Result<Void> bindContact(@RequestBody @Valid AppBindContactRequest request) {
        auth.bindContact(SecurityUser.getUserId(), request);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid AppChangePasswordRequest request) {
        auth.changePassword(SecurityUser.getUserId(), request);
        return new Result<Void>().ok(null);
    }
}
