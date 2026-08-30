package xiaozhi.modules.conversation.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_companion_conversation")
public class CompanionConversationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long ownerId;
    private String profileId;
    private Integer profileVersionNo;
    private String source;
    private String title;
    private Date lastActivityAt;
    private Date deletedAt;
    private Date createdAt;
    private Date updatedAt;
}
