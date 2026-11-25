package zixuan.modules.appauth.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 改自己的昵称或头像。不传的字段保持不变，传空字符串表示清掉。
 */
@Data
@Schema(description = "修改昵称和头像")
public class AppProfileUpdateDTO implements Serializable {

    @Schema(description = "昵称，最多 20 个字；空字符串表示不用昵称")
    private String nickname;

    @Schema(description = "头像 data URL（image/jpeg、png 或 webp，base64）；空字符串表示移除头像")
    private String avatar;
}
