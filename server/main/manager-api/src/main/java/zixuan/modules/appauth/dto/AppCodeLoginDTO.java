package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * App 验证码登录表单
 */
@Data
@Schema(description = "App 验证码登录表单")
public class AppCodeLoginDTO implements Serializable {

    @Schema(description = "手机号（含国际区号）")
    @NotBlank(message = "{sysuser.username.require}")
    private String phone;

    @Schema(description = "短信验证码")
    @NotBlank(message = "短信验证码不能为空")
    private String code;

    @Schema(description = "邀请码。手机号还没有账号、且开启了邀请制时必填")
    private String inviteCode;

    @Schema(description = "用户在登录页勾选同意的协议版本。提供时记录一次同意")
    private String consentVersion;
}
