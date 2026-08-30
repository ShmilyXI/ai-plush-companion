package xiaozhi.modules.appauth.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_app_auth_challenge")
public class AppAuthChallengeEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String channel;

    private String purpose;

    private String normalizedValue;

    private String codeHash;

    private Date expiresAt;

    private Date consumedAt;

    private Integer failedAttempts;

    private Date createdAt;
}
