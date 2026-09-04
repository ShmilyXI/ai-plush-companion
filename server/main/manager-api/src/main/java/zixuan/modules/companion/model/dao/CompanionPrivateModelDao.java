package zixuan.modules.companion.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.companion.model.entity.CompanionPrivateModelEntity;

@Mapper
public interface CompanionPrivateModelDao extends BaseMapper<CompanionPrivateModelEntity> {
    @Select("SELECT * FROM ai_companion_private_model WHERE id=#{id} AND user_id=#{userId} LIMIT 1")
    CompanionPrivateModelEntity selectOwned(@Param("userId") Long userId, @Param("id") String id);

    @Select("SELECT * FROM ai_companion_private_model WHERE id=#{id} AND user_id=#{userId} LIMIT 1 FOR UPDATE")
    CompanionPrivateModelEntity selectOwnedForUpdate(@Param("userId") Long userId, @Param("id") String id);

    @Select("SELECT * FROM ai_companion_private_model WHERE user_id=#{userId} AND model_type=#{modelType} ORDER BY created_at DESC")
    List<CompanionPrivateModelEntity> selectOwnedByType(@Param("userId") Long userId,
            @Param("modelType") String modelType);
}
