package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "注销账号")
public class AppAccountDeleteDTO implements Serializable {
    @Schema(description = "发到账号手机号上的短信验证码，用来确认是本人操作")
    @NotBlank(message = "短信验证码不能为空")
    private String code;
}
