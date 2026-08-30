package xiaozhi.modules.appauth.dao;

import java.util.Date;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.appauth.entity.AppRefreshTokenEntity;

@Mapper
public interface AppRefreshTokenDao extends BaseMapper<AppRefreshTokenEntity> {

    @Select("SELECT * FROM ai_app_refresh_token WHERE token_hash=#{tokenHash} LIMIT 1")
    AppRefreshTokenEntity findRefreshToken(@Param("tokenHash") String tokenHash);

    @Update("UPDATE ai_app_refresh_token SET revoked_at=#{revokedAt} "
            + "WHERE id=#{id} AND revoked_at IS NULL")
    int revokeRefreshToken(@Param("id") String id, @Param("revokedAt") Date revokedAt);
}
