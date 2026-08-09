package xiaozhi.modules.security.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "密码登录表单")
public class PasswordLoginDTO implements Serializable {

    @Schema(description = "手机号码")
    @NotBlank(message = "{sysuser.username.require}")
    private String username;

    @Schema(description = "密码")
    @NotBlank(message = "{sysuser.password.require}")
    private String password;

    @Schema(description = "旧客户端登录协议标识")
    private String captchaId;
}
