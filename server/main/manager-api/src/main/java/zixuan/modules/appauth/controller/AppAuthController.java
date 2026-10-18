package zixuan.modules.appauth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.appauth.dto.AppCodeLoginDTO;
import zixuan.modules.appauth.dto.AppPasswordLoginDTO;
import zixuan.modules.appauth.dto.AppRefreshDTO;
import zixuan.modules.appauth.dto.AppRegisterDTO;
import zixuan.modules.appauth.dto.AppSmsCodeDTO;
import zixuan.modules.appauth.service.AppAuthService;
import zixuan.modules.appauth.vo.AppProfileVO;
import zixuan.modules.appauth.vo.AppTokenVO;
import zixuan.modules.security.user.SecurityUser;

/**
 * 消费者 App 认证
 */
@AllArgsConstructor
@RestController
@RequestMapping("/app/v1/auth")
@Tag(name = "App 认证")
public class AppAuthController {

    private static final String DEVICE_LABEL_HEADER = "X-Device-Label";

    private final AppAuthService appAuthService;

    @PostMapping("/sms-code")
    @Operation(summary = "发送短信验证码")
    public Result<Void> smsCode(@RequestBody @Valid AppSmsCodeDTO dto, HttpServletRequest request) {
        appAuthService.sendSmsCode(dto.getPhone(), request.getRemoteAddr());
        return new Result<>();
    }

    @PostMapping("/register")
    @Operation(summary = "注册（手机号+验证码+密码）")
    public Result<AppTokenVO> register(@RequestBody @Valid AppRegisterDTO dto, HttpServletRequest request,
            @RequestHeader(value = DEVICE_LABEL_HEADER, required = false) String deviceLabel) {
        return new Result<AppTokenVO>().ok(
                appAuthService.register(dto, deviceLabel, request.getRemoteAddr()));
    }

    @PostMapping("/login-password")
    @Operation(summary = "密码登录（手机号+密码）")
    public Result<AppTokenVO> loginPassword(@RequestBody @Valid AppPasswordLoginDTO dto, HttpServletRequest request,
            @RequestHeader(value = DEVICE_LABEL_HEADER, required = false) String deviceLabel) {
        return new Result<AppTokenVO>().ok(
                appAuthService.loginByPassword(dto, deviceLabel, request.getRemoteAddr()));
    }

    @PostMapping("/login-code")
    @Operation(summary = "验证码登录（手机号+验证码，未注册自动创建账号）")
    public Result<AppTokenVO> loginCode(@RequestBody @Valid AppCodeLoginDTO dto, HttpServletRequest request,
            @RequestHeader(value = DEVICE_LABEL_HEADER, required = false) String deviceLabel) {
        return new Result<AppTokenVO>().ok(
                appAuthService.loginBySmsCode(dto, deviceLabel, request.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新访问令牌")
    public Result<AppTokenVO> refresh(@RequestBody @Valid AppRefreshDTO dto) {
        return new Result<AppTokenVO>().ok(appAuthService.refresh(dto.getRefreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出当前会话")
    public Result<Void> logout() {
        appAuthService.logout(SecurityUser.getUser().getToken());
        return new Result<>();
    }

    @GetMapping("/profile")
    @Operation(summary = "当前登录用户信息")
    public Result<AppProfileVO> profile() {
        return new Result<AppProfileVO>().ok(appAuthService.profile(SecurityUser.getUserId()));
    }
}
