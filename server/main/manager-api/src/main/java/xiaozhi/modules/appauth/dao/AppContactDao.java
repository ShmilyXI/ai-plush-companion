package xiaozhi.modules.appauth.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.appauth.entity.AppContactEntity;

@Mapper
public interface AppContactDao extends BaseMapper<AppContactEntity> {

    @Select("SELECT * FROM ai_app_contact WHERE channel=#{channel} "
            + "AND normalized_value=#{normalizedValue} LIMIT 1")
    AppContactEntity findByNormalizedValue(@Param("channel") String channel,
            @Param("normalizedValue") String normalizedValue);

    @Select("SELECT * FROM ai_app_contact WHERE user_id=#{userId} ORDER BY channel")
    List<AppContactEntity> findByUserId(@Param("userId") Long userId);
}
