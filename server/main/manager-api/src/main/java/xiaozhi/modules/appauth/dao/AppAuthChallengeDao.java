package xiaozhi.modules.appauth.dao;

import java.util.Date;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.appauth.entity.AppAuthChallengeEntity;

@Mapper
public interface AppAuthChallengeDao extends BaseMapper<AppAuthChallengeEntity> {

    @Select("SELECT * FROM ai_app_auth_challenge WHERE channel=#{channel} "
            + "AND normalized_value=#{normalizedValue} AND purpose=#{purpose} "
            + "AND consumed_at IS NULL AND expires_at > NOW(3) "
            + "ORDER BY created_at DESC LIMIT 1")
    AppAuthChallengeEntity findActiveChallenge(@Param("channel") String channel,
            @Param("normalizedValue") String normalizedValue, @Param("purpose") String purpose);

    @Update("UPDATE ai_app_auth_challenge SET consumed_at=#{consumedAt} "
            + "WHERE id=#{id} AND consumed_at IS NULL")
    int consumeChallenge(@Param("id") String id, @Param("consumedAt") Date consumedAt);

    @Update("UPDATE ai_app_auth_challenge SET failed_attempts=failed_attempts + 1 "
            + "WHERE id=#{id} AND consumed_at IS NULL")
    int incrementFailedAttempts(@Param("id") String id);
}
