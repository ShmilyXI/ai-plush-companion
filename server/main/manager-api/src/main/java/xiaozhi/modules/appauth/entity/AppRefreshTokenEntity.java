package xiaozhi.modules.appauth.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_app_refresh_token")
public class AppRefreshTokenEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String tokenHash;

    private Date expiresAt;

    private Date revokedAt;

    private Date lastUsedAt;

    private Date createdAt;
}
