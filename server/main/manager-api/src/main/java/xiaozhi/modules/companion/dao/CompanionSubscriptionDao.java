package xiaozhi.modules.companion.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.entity.CompanionSubscriptionEntity;

@Mapper
public interface CompanionSubscriptionDao extends BaseMapper<CompanionSubscriptionEntity> {
    @Select("SELECT * FROM ai_companion_subscription WHERE user_id = #{userId} AND status = 'active' FOR UPDATE")
    CompanionSubscriptionEntity selectActiveByUserIdForUpdate(@Param("userId") Long userId);
}
