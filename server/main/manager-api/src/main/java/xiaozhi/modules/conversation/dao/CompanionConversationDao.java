package xiaozhi.modules.conversation.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.conversation.entity.CompanionConversationEntity;

@Mapper
public interface CompanionConversationDao extends BaseMapper<CompanionConversationEntity> {
    @Select("SELECT * FROM ai_companion_conversation WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NULL LIMIT 1 FOR UPDATE")
    CompanionConversationEntity selectOwned(@Param("id") String id, @Param("ownerId") Long ownerId);

    @Select("SELECT * FROM ai_companion_conversation WHERE owner_id=#{ownerId} AND deleted_at IS NULL ORDER BY last_activity_at DESC, id DESC LIMIT #{limit}")
    List<CompanionConversationEntity> selectRecent(@Param("ownerId") Long ownerId, @Param("limit") int limit);

    @Update("UPDATE ai_companion_conversation SET title=#{title}, updated_at=#{updatedAt} WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NULL")
    int rename(@Param("id") String id, @Param("ownerId") Long ownerId, @Param("title") String title,
            @Param("updatedAt") java.util.Date updatedAt);

    @Update("UPDATE ai_companion_conversation SET deleted_at=#{deletedAt}, updated_at=#{deletedAt} WHERE id=#{id} AND owner_id=#{ownerId} AND deleted_at IS NULL")
    int softDelete(@Param("id") String id, @Param("ownerId") Long ownerId, @Param("deletedAt") java.util.Date deletedAt);

    @Update("UPDATE ai_companion_conversation SET title=#{title}, last_activity_at=#{lastActivityAt}, updated_at=#{updatedAt} WHERE id=#{id} AND deleted_at IS NULL")
    int touch(@Param("id") String id, @Param("title") String title,
            @Param("lastActivityAt") java.util.Date lastActivityAt,
            @Param("updatedAt") java.util.Date updatedAt);
}
