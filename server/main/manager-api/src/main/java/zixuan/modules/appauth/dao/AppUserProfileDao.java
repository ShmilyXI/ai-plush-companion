package zixuan.modules.appauth.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import zixuan.modules.appauth.entity.AppUserProfileEntity;

/**
 * App 用户的昵称和头像。昵称和头像分开写，改一个不会碰另一个。
 */
@Mapper
public interface AppUserProfileDao {

    @Select("SELECT user_id, nickname, avatar FROM app_user_profile WHERE user_id = #{userId}")
    AppUserProfileEntity find(@Param("userId") Long userId);

    @Insert("INSERT INTO app_user_profile (user_id, nickname, update_date) VALUES (#{userId}, #{nickname}, NOW()) "
            + "ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), update_date = NOW()")
    int saveNickname(@Param("userId") Long userId, @Param("nickname") String nickname);

    @Insert("INSERT INTO app_user_profile (user_id, avatar, update_date) VALUES (#{userId}, #{avatar}, NOW()) "
            + "ON DUPLICATE KEY UPDATE avatar = VALUES(avatar), update_date = NOW()")
    int saveAvatar(@Param("userId") Long userId, @Param("avatar") String avatar);

    @Delete("DELETE FROM app_user_profile WHERE user_id = #{userId}")
    int delete(@Param("userId") Long userId);
}
