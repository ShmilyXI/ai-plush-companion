package zixuan.modules.conversation.entity;

import java.time.LocalDate;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一轮对话的用量。只有数量和耗时，没有对话内容。
 */
@Data
@TableName("ai_conversation_usage")
public class ConversationUsageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate usageDate;
    private Long userId;
    private String agentId;
    private String conversationId;
    private String turnId;
    private Integer firstParty;
    private String inputMode;
    private String llmModel;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer inputChars;
    private Integer replyChars;
    private Integer ttsChars;
    private Integer firstTextMs;
    private Integer totalMs;
    private Date createdAt;
}
