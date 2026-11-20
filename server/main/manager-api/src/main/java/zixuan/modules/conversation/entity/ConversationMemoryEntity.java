package zixuan.modules.conversation.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 角色记得的关于用户的一件事。 */
@Data
@TableName("ai_conversation_memory")
public class ConversationMemoryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String agentId;
    private String content;
    /** conversation：从对话里记下的；user：用户自己写的。 */
    private String source;
    private Date createdAt;
}
