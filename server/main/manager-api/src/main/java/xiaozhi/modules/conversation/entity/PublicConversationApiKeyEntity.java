package xiaozhi.modules.conversation.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_public_conversation_api_key")
public class PublicConversationApiKeyEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long userId;
    private String name;
    private String keyPrefix;
    private String keyHash;
    private String scopesJson;
    private String agentIdsJson;
    private Date expiresAt;
    private Integer revoked;
    private Date lastUsedAt;
    private Date createdAt;
    private Date updatedAt;
}
