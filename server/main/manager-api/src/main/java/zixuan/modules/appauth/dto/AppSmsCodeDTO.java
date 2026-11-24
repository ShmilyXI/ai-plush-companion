package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * App 短信验证码申请表单
 */
@Data
@Schema(description = "App 短信验证码申请表单")
public class AppSmsCodeDTO implements Serializable {

    @Schema(description = "手机号（含国际区号，如 +8613800138000）")
    @NotBlank(message = "{sysuser.username.require}")
    private String phone;

    @Schema(description = "图形验证码的编号：调用方生成，取图片时传给 /user/captcha 的 uuid")
    @NotBlank(message = "请输入图形验证码")
    private String captchaId;

    @Schema(description = "图片上的字符，不区分大小写")
    @NotBlank(message = "请输入图形验证码")
    private String captcha;
}
