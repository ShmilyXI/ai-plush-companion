package xiaozhi.modules.companion.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_companion_subscription")
@Schema(description = "陪伴订阅")
public class CompanionSubscriptionEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_CANCELLED = "cancelled";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    private String planId;

    private String status;

    private Date startsAt;

    private Date expiresAt;

    private Date createdAt;

    private Date updatedAt;
}
