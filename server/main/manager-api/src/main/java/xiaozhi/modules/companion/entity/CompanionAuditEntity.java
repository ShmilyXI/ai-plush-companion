package xiaozhi.modules.companion.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_companion_audit")
@Schema(description = "陪伴后台审计记录")
public class CompanionAuditEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long operatorId;

    private Long targetUserId;

    private String action;

    private String resourceType;

    private String resourceId;

    @Schema(description = "脱敏摘要，不包含密码、令牌或完整记忆")
    private String summary;

    private Date createdAt;
}
