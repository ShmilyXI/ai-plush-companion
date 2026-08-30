package xiaozhi.modules.conversation.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_companion_conversation_turn")
public class CompanionConversationTurnEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String turnId;
    private String requestId;
    private String source;
    private String userText;
    private String assistantText;
    private Date occurredAt;
    private Date createdAt;
}
