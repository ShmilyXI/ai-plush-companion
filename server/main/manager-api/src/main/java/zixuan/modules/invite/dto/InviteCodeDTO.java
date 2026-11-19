package zixuan.modules.invite.dto;

import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "邀请码")
public class InviteCodeDTO {
    private String id;
    private String code;
    private Integer maxUses;
    private Integer usedCount;
    private Date expireDate;

    @Schema(description = "是否启用")
    private boolean enabled;

    @Schema(description = "现在是否还能用：启用、未过期且还有名额")
    private boolean usable;

    private String remark;
    private Date createDate;
}
