package zixuan.modules.conversation.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一轮对话的文字记录，归属于“用户 + 角色”。
 */
@Data
@TableName("ai_conversation_turn")
public class ConversationTurnEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String agentId;
    private String conversationId;
    private String turnId;
    private String userText;
    private String replyText;
    private Date createdAt;
}
