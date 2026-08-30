package xiaozhi.modules.appauth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.user.SecurityUser;

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
    public Result<Void> logout() { service.logout(SecurityUser.getUserId()); return new Result<Void>().ok(null); }

    @PostMapping("/contacts")
    public Result<Void> bindContact(@RequestBody @Valid AppBindContactRequest request) {
        service.bindContact(SecurityUser.getUserId(), request); return new Result<Void>().ok(null);
    }
}
