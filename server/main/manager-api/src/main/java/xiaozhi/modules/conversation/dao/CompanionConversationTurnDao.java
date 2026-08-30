package xiaozhi.modules.conversation.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;

@Mapper
public interface CompanionConversationTurnDao extends BaseMapper<CompanionConversationTurnEntity> {
    @Select("SELECT * FROM ai_companion_conversation_turn WHERE conversation_id=#{conversationId} AND turn_id=#{turnId} LIMIT 1")
    CompanionConversationTurnEntity selectByConversationAndTurn(@Param("conversationId") String conversationId,
            @Param("turnId") String turnId);

    @Select("SELECT * FROM ai_companion_conversation_turn WHERE conversation_id=#{conversationId} ORDER BY occurred_at ASC, id ASC")
    List<CompanionConversationTurnEntity> selectByConversation(@Param("conversationId") String conversationId);
}
