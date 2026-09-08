package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * App 密码登录表单
 */
@Data
@Schema(description = "App 密码登录表单")
public class AppPasswordLoginDTO implements Serializable {

    @Schema(description = "手机号（含国际区号）")
    @NotBlank(message = "{sysuser.username.require}")
    private String phone;

    @Schema(description = "SM2 加密后的密码")
    @NotBlank(message = "{sysuser.password.require}")
    private String password;

    @Schema(description = "旧客户端登录协议标识，可空")
    private String captchaId;
}
