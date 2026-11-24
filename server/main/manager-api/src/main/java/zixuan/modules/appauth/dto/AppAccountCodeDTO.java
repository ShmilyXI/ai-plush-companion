package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 已登录用户核对发到自己手机上的验证码，比如改密码前先确认是本人。
 */
@Data
@Schema(description = "核对当前账号的短信验证码")
public class AppAccountCodeDTO implements Serializable {

    @Schema(description = "发到当前账号手机号上的短信验证码")
    @NotBlank(message = "短信验证码不能为空")
    private String code;
}
