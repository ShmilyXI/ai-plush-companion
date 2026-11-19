package zixuan.modules.appauth.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 账号注销和协议同意记录用到的语句。
 *
 * 注销时，属于这个人的内容全部删除；只剩数量、不含内容的统计记录改为匿名，
 * 这样既不再能对应到任何人，也不影响整体的用量和安全统计。
 */
@Mapper
public interface AccountDataDao {

    @Insert("INSERT IGNORE INTO ai_user_consent (user_id, version, agreed_at) VALUES (#{userId}, #{version}, NOW(3))")
    int recordConsent(@Param("userId") Long userId, @Param("version") String version);

    @Delete("DELETE FROM ai_user_consent WHERE user_id = #{userId}")
    int deleteConsents(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_conversation_turn WHERE user_id = #{userId}")
    int deleteConversationTurns(@Param("userId") Long userId);

    @Update("UPDATE ai_conversation_usage SET user_id = 0 WHERE user_id = #{userId}")
    int anonymizeUsage(@Param("userId") Long userId);

    @Update("UPDATE ai_safety_event SET user_id = 0 WHERE user_id = #{userId}")
    int anonymizeSafetyEvents(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_invite_code_use WHERE user_id = #{userId}")
    int deleteInviteUse(@Param("userId") Long userId);

    @Delete("DELETE FROM app_user_token WHERE user_id = #{userId}")
    int deleteAppTokens(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_token WHERE user_id = #{userId}")
    int deleteConsoleTokens(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_companion_global_model_credential WHERE user_id = #{userId}")
    int deleteModelCredentials(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_companion_private_model WHERE user_id = #{userId}")
    int deletePrivateModels(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_companion_subscription WHERE user_id = #{userId}")
    int deleteSubscriptions(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_public_conversation_api_key WHERE user_id = #{userId}")
    int deleteApiKeys(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_voice_clone WHERE user_id = #{userId}")
    int deleteVoiceClones(@Param("userId") Long userId);

    @Delete("DELETE FROM ai_screen_scene WHERE user_id = #{userId}")
    int deleteScreenScenes(@Param("userId") Long userId);
}
