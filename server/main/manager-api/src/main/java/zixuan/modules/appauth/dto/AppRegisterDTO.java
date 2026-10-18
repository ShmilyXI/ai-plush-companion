package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * App 注册表单：手机号 + 短信验证码 + SM2 加密密码
 */
@Data
@Schema(description = "App 注册表单")
public class AppRegisterDTO implements Serializable {

    @Schema(description = "手机号（含国际区号）")
    @NotBlank(message = "{sysuser.username.require}")
    private String phone;

    @Schema(description = "短信验证码")
    @NotBlank(message = "短信验证码不能为空")
    private String code;

    @Schema(description = "SM2 加密后的密码")
    @NotBlank(message = "密码不能为空")
    private String password;
}
