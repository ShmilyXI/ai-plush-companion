package zixuan.modules.invite.dto;

import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "生成邀请码")
public class InviteCodeCreateDTO {
    @Schema(description = "生成几个，默认 1，最多 100")
    private Integer count;

    @Schema(description = "每个邀请码可以使用几次，默认 1")
    private Integer maxUses;

    @Schema(description = "失效时间，空表示不过期")
    private Date expireDate;

    @Schema(description = "备注，例如发给了谁")
    private String remark;
}
