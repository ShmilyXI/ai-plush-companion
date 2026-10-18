package zixuan.modules.conversation.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.conversation.entity.PublicConversationApiKeyEntity;

@Mapper
public interface PublicConversationApiKeyDao extends BaseMapper<PublicConversationApiKeyEntity> {
    @Select("SELECT * FROM ai_public_conversation_api_key WHERE key_hash=#{keyHash} LIMIT 1")
    PublicConversationApiKeyEntity selectByHash(@Param("keyHash") String keyHash);

    @Select("SELECT * FROM ai_public_conversation_api_key WHERE user_id=#{userId} ORDER BY created_at DESC")
    List<PublicConversationApiKeyEntity> selectByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM ai_public_conversation_api_key WHERE id=#{id} AND user_id=#{userId} LIMIT 1 FOR UPDATE")
    PublicConversationApiKeyEntity selectOwnedForUpdate(@Param("userId") Long userId, @Param("id") String id);
}
