package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * App 刷新令牌表单
 */
@Data
@Schema(description = "App 刷新令牌表单")
public class AppRefreshDTO implements Serializable {

    @Schema(description = "刷新令牌")
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
