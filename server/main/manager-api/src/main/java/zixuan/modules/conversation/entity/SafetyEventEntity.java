package zixuan.modules.conversation.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次内容安全命中。只有类别和处理方式，没有对话内容。
 */
@Data
@TableName("ai_safety_event")
public class SafetyEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String agentId;
    private String conversationId;
    private String turnId;
    private String direction;
    private String action;
    private String category;
    private Date createdAt;
}
