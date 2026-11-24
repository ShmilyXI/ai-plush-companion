package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 已登录用户设置或修改密码：用发到自己手机上的验证码确认是本人。
 */
@Data
@Schema(description = "设置或修改密码")
public class AppSetPasswordDTO implements Serializable {

    @Schema(description = "发到当前账号手机号上的短信验证码")
    @NotBlank(message = "短信验证码不能为空")
    private String code;

    @Schema(description = "SM2 加密后的新密码")
    @NotBlank(message = "密码不能为空")
    private String password;
}
