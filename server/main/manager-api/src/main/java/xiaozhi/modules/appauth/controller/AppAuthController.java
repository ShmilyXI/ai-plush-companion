package xiaozhi.modules.appauth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.appauth.AppAuthCodeRequest;
import xiaozhi.modules.appauth.AppAuthCodeVO;
import xiaozhi.modules.appauth.AppAuthService;
import xiaozhi.modules.appauth.AppAuthTokenVO;
import xiaozhi.modules.appauth.AppPasswordLoginRequest;
import xiaozhi.modules.appauth.AppCodeLoginRequest;
import xiaozhi.modules.appauth.AppRegisterRequest;
import xiaozhi.modules.appauth.AppResetPasswordRequest;
import xiaozhi.modules.appauth.AppRefreshRequest;
import xiaozhi.modules.appauth.AppBindContactRequest;

@RestController
@RequestMapping("/app/auth")
public class AppAuthController {
    private final AppAuthService service;

    public AppAuthController(AppAuthService service) {
        this.service = service;
    }

    @PostMapping("/code")
    public Result<AppAuthCodeVO> code(@RequestBody @Valid AppAuthCodeRequest request) {
        return new Result<AppAuthCodeVO>().ok(service.sendCode(request));
    }

    @PostMapping("/password-login")
    public Result<AppAuthTokenVO> passwordLogin(@RequestBody @Valid AppPasswordLoginRequest request) {
        return new Result<AppAuthTokenVO>().ok(service.passwordLogin(request));
    }

    @PostMapping("/code-login")
    public Result<AppAuthTokenVO> codeLogin(@RequestBody @Valid AppCodeLoginRequest request) {
        return new Result<AppAuthTokenVO>().ok(service.codeLogin(request));
    }

    @PostMapping("/register")
    public Result<AppAuthTokenVO> register(@RequestBody @Valid AppRegisterRequest request) {
        return new Result<AppAuthTokenVO>().ok(service.register(request));
    }

    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody @Valid AppResetPasswordRequest request) {
        service.resetPassword(request); return new Result<Void>().ok(null);
    }

    @PostMapping("/refresh")
    public Result<AppAuthTokenVO> refresh(@RequestBody @Valid AppRefreshRequest request) {
        return new Result<AppAuthTokenVO>().ok(service.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> logout() { service.logout(SecurityUser.getUserId()); return new Result<Void>().ok(null); }

    @PostMapping("/contacts")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> bindContact(@RequestBody @Valid AppBindContactRequest request) {
        service.bindContact(SecurityUser.getUserId(), request); return new Result<Void>().ok(null);
    }
}
